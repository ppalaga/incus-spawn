package dev.incusspawn.git;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static dev.incusspawn.incus.Container.shellQuote;

/** A local path or file URL names a required host repository, never a remote to fetch. */
public final class HostRepoSource {
    public static final String CONFIG_KEY = "isx.hostOnly";

    private HostRepoSource() {}

    public static boolean isHostOnly(String url) {
        if (url == null || url.isBlank()) return false;
        if (url.regionMatches(true, 0, "file:", 0, 5)) return true;
        // Preserve URL schemes and scp-style Git remotes (user@host:path).
        // A colon after a slash is part of a local pathname.
        int colon = url.indexOf(':');
        int slash = url.indexOf('/');
        return colon < 0 || (slash >= 0 && slash < colon);
    }

    public static Path path(String url) {
        return parsePath(url).normalize();
    }

    private static Path parsePath(String url) {
        try {
            if (!isHostOnly(url)) throw new IllegalArgumentException("Not a host repository path");
            var value = url;
            if (url.regionMatches(true, 0, "file:", 0, 5)) {
                value = url.substring(5);
                boolean singleName = false;
                if (value.startsWith("//")) {
                    value = value.substring(2);
                    // file://repo is a shorthand; file://host/path is an authority.
                    singleName = !value.startsWith("/") && !value.startsWith("~/")
                            && !value.startsWith("./") && !value.startsWith("../");
                    if (singleName && (value.contains("/") || value.contains(":") || value.contains("@"))) {
                        throw new IllegalArgumentException("File URL hosts are not supported; use file:dir/repo for a relative path");
                    }
                }
                boolean absolute = value.startsWith("/");
                var uri = URI.create("file:" + (absolute ? "" : "/") + value);
                if (uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new IllegalArgumentException("File URLs must not have a host, query or fragment");
                }
                value = absolute ? uri.getPath() : uri.getPath().substring(1);
                if ((!absolute && value.startsWith("/"))
                        || (singleName && (value.contains("/") || value.contains(":") || value.contains("@")))) {
                    throw new IllegalArgumentException("Encoded separators must not change the file URL's path form");
                }
            }
            if (value.isBlank() || value.startsWith("//")) throw new IllegalArgumentException("Expected a local path");
            if (value.startsWith("~") && !value.equals("~") && !value.startsWith("~/")) {
                throw new IllegalArgumentException("Only the current host user's ~/ is supported");
            }
            var path = Path.of(value);
            if (value.equals("~") || value.startsWith("~/")) {
                path = Path.of(System.getProperty("user.home")).resolve(value.equals("~") ? "" : value.substring(2));
            }
            return path;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid host repository '" + url + "': " + e.getMessage(), e);
        }
    }

    public static String repoName(String url) {
        var source = path(url);
        if (source.getFileName() == null) return "";
        if (source.getFileName().toString().equals(".git")) source = source.getParent();
        if (source == null) return "";
        var name = source.getFileName() == null ? "" : source.getFileName().toString();
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    public static Path gitDirectory(String url) {
        return gitDirectory(url, null);
    }

    /** Resolve only self-contained Git directories; never discover repos in ancestor directories. */
    public static Path gitDirectory(String url, Path templateDirectory) {
        var source = parsePath(url);
        boolean relative = !source.isAbsolute();
        if (relative) {
            for (var component : source) {
                if (component.toString().equals("..")) {
                    throw new IllegalArgumentException("Relative host repository paths must not contain '..'; use an explicit absolute path: " + url);
                }
            }
            if (templateDirectory == null) {
                throw new IllegalArgumentException("Relative host repository requires a template file location: " + url);
            }
            source = templateDirectory.resolve(source).toAbsolutePath().normalize();
        }
        source = source.normalize();
        if (Files.isDirectory(source.resolve(".git"))) source = source.resolve(".git");
        if (relative) {
            try {
                if (!source.toRealPath().startsWith(templateDirectory.toRealPath())) {
                    throw new IllegalArgumentException("Relative host repository escapes the template directory: " + url);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot resolve host repository: " + source, e);
            }
        }
        if (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("HEAD"))
                || !Files.isDirectory(source.resolve("objects")) || Files.exists(source.resolve("commondir"))) {
            throw new IllegalArgumentException("Host repository must be a working copy with a standalone .git directory, a .git directory or a bare repository: " + source);
        }
        if (Files.exists(source.resolve("objects/info/alternates"))
                || Files.exists(source.resolve("objects/info/http-alternates"))
                || Files.isSymbolicLink(source.resolve("objects")) || Files.exists(source.resolve("shallow"))) {
            throw new IllegalArgumentException("Host repository must be complete and have no external object dependencies: " + source);
        }
        return source;
    }

    public record ConfigEntry(String key, String value) {}
    public record Snapshot(Path gitDirectory, String head, String branch, String refs, List<ConfigEntry> config) {
        public String fingerprint() {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                for (var value : List.of(head, branch, refs)) {
                    digest.update(value.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
                for (var entry : config) {
                    digest.update(entry.key().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(entry.value().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * A local mirror preserves all ref namespaces. Convert it to a normal working
         * repository, replacing only the clone-generated config, then check out HEAD.
         * No host config, hooks, index or working tree is copied wholesale.
         */
        public String cloneScript(String mountedGitDir, String destination) {
            var git = "git -C " + shellQuote(destination);
            var script = new StringBuilder("set -eu\n")
                    .append("export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_NO_LAZY_FETCH=1 GIT_ALLOW_PROTOCOL=file GIT_LFS_SKIP_SMUDGE=1\n")
                    .append("test ! -e ").append(shellQuote(destination)).append("\n")
                    .append("git -c core.hooksPath=/dev/null clone --local --mirror --no-hardlinks --origin isx-source -- ")
                    .append(shellQuote(mountedGitDir)).append(' ').append(shellQuote(destination + "/.git")).append("\n")
                    .append("git --git-dir=").append(shellQuote(destination + "/.git"))
                    .append(" config core.bare false\n")
                    .append(git).append(" config --remove-section remote.isx-source\n");
            // Install the captured refs explicitly: uploadpack.hideRefs may hide some
            // from clone's advertisement, although local cloning copies their objects.
            script.append(git).append(" update-ref --no-deref --stdin <<'ISX_HOST_REFS'\n");
            for (var line : refs.lines().toList()) {
                var fields = line.split(" ", 3);
                script.append("update ").append(fields[0]).append(' ').append(fields[1]).append('\n');
            }
            script.append("ISX_HOST_REFS\n");
            // Mirror cloning resolves symbolic refs other than HEAD. Restore those
            // explicitly (e.g. upstream/HEAD).
            for (var line : refs.lines().toList()) {
                var fields = line.split(" ", 3);
                if (fields.length == 3 && !fields[2].isEmpty()) {
                    script.append(git).append(" symbolic-ref ").append(shellQuote(fields[0]))
                            .append(' ').append(shellQuote(fields[2])).append("\n");
                }
            }
            for (var entry : config) {
                script.append(git).append(" config --add ").append(shellQuote(entry.key()))
                        .append(' ').append(shellQuote(entry.value())).append("\n");
            }
            script.append(git).append(" config ").append(CONFIG_KEY).append(" true\n");
            if (branch.isEmpty()) {
                script.append(git).append(" update-ref --no-deref HEAD ").append(shellQuote(head)).append("\n");
            } else {
                if (!head.isEmpty()) {
                    script.append(git).append(" update-ref ").append(shellQuote(branch)).append(' ').append(shellQuote(head)).append("\n");
                }
                script.append(git).append(" symbolic-ref HEAD ").append(shellQuote(branch)).append("\n");
            }
            // read-tree populates the index/worktree without checkout hooks or smudge
            // filters inherited from the host. An empty repository has no tree yet.
            if (!head.isEmpty()) {
                script.append(git).append(" -c core.hooksPath=/dev/null read-tree --reset -u HEAD\n");
            }
            script.append(git).append(" fsck --connectivity-only --no-dangling\n");
            return script.toString();
        }
    }

    public static Snapshot capture(String url) {
        return capture(url, null);
    }

    public static Snapshot capture(String url, Path templateDirectory) {
        var source = gitDirectory(url, templateDirectory);
        var config = new ArrayList<ConfigEntry>();
        var rawConfig = git(source, true, "config", "--includes", "--null", "--get-regexp", "^(remote\\.|branch\\.)");
        for (var item : rawConfig.split("\u0000")) {
            if (item.isEmpty()) continue;
            int split = item.indexOf('\n');
            var key = split < 0 ? item : item.substring(0, split);
            // A valueless config entry is an implicit boolean true, not an empty value.
            var value = split < 0 ? "true" : item.substring(split + 1);
            if (key.endsWith(".promisor") || key.endsWith(".partialclonefilter")) {
                throw new IllegalArgumentException("Partial host repositories are not supported: " + source);
            }
            config.add(new ConfigEntry(key, value));
        }
        var branch = git(source, true, "symbolic-ref", "--quiet", "HEAD").strip();
        var head = git(source, true, "rev-parse", "--verify", "--quiet", "HEAD^{commit}").strip();
        if (head.isEmpty() && branch.isEmpty()) throw new IllegalArgumentException("Invalid host HEAD: " + source);
        var refs = git(source, false, "for-each-ref", "--format=%(refname) %(objectname) %(symref)");
        if (!branch.isEmpty() && !head.isEmpty()
                && refs.lines().noneMatch(line -> line.startsWith(branch + " " + head + " "))) {
            throw new IllegalStateException("Host HEAD changed while reading repository; retry: " + source);
        }
        return new Snapshot(source, head, branch, refs, List.copyOf(config));
    }

    public static String fingerprint(String url) {
        return fingerprint(url, null);
    }

    public static String fingerprint(String url, Path templateDirectory) {
        try {
            return capture(url, templateDirectory).fingerprint();
        } catch (IllegalArgumentException | IllegalStateException e) {
            // An unavailable source makes a previously built template outdated, but
            // must not prevent the TUI from displaying it. Building reports the error.
            return "unavailable";
        }
    }

    private static String git(Path source, boolean allowMissing, String... args) {
        var command = new ArrayList<>(List.of("git", "--git-dir=" + source));
        command.addAll(List.of(args));
        Process process = null;
        try {
            var builder = new ProcessBuilder(command);
            // A caller's GIT_DIR, worktree or injected config must not change the source.
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            builder.environment().put("GIT_NO_LAZY_FETCH", "1");
            builder.environment().put("GIT_ALLOW_PROTOCOL", "file");
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0 && !(allowMissing && code == 1)) {
                throw new IllegalStateException("Cannot read host repository " + source + " (git " + args[0] + " exited " + code + ")");
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read host repository " + source, e);
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading host repository " + source, e);
        }
    }
}
