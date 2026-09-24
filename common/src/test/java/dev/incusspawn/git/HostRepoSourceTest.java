package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.git.GitTestUtils.runGit;
import static org.junit.jupiter.api.Assertions.*;

class HostRepoSourceTest {
    @TempDir Path tempDir;

    private Path repository(String name) throws Exception {
        var dir = Files.createDirectories(tempDir.resolve(name));
        runGit(dir, "init", "-b", "feature/local");
        runGit(dir, "config", "user.name", "Test");
        runGit(dir, "config", "user.email", "test@example.com");
        Files.writeString(dir.resolve("tracked"), "committed\n");
        runGit(dir, "add", "tracked");
        runGit(dir, "commit", "-m", "Unpushed local commit");
        return dir;
    }

    private String url(Path repo) { return repo.resolve(".git").toUri().toString(); }

    private void shell(String script) throws Exception {
        var process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private Path cloneRepo(Path source, String name) throws Exception {
        var destination = tempDir.resolve(name);
        var snapshot = HostRepoSource.capture(source.toString());
        shell(snapshot.cloneScript(source.resolve(".git").toString(), destination.toString()));
        return destination;
    }

    @Test
    void preservesHeadRefsRemotesAndTrackingWithoutFetchingOrCopyingDirtyFiles() throws Exception {
        var source = repository("Host project 'with spaces'");
        var head = runGit(source, "rev-parse", "HEAD").strip();
        runGit(source, "remote", "add", "origin", "https://invalid.example/fork.git");
        runGit(source, "remote", "add", "upstream", "git@invalid.example:upstream.git");
        runGit(source, "config", "--add", "remote.origin.url", "https://invalid.example/second.git");
        runGit(source, "config", "--add", "remote.origin.pushurl", "ssh://git@invalid.example/push.git");
        runGit(source, "config", "--add", "remote.origin.pushurl", "ssh://git@invalid.example/second-push.git");
        runGit(source, "config", "remote.origin.tagOpt", "--no-tags");
        runGit(source, "update-ref", "refs/remotes/upstream/main", head);
        runGit(source, "symbolic-ref", "refs/remotes/upstream/HEAD", "refs/remotes/upstream/main");
        runGit(source, "config", "branch.feature/local.remote", "upstream");
        runGit(source, "config", "branch.feature/local.merge", "refs/heads/main");
        runGit(source, "branch", "other");
        runGit(source, "config", "uploadpack.hideRefs", "refs/heads/other");
        runGit(source, "tag", "local-tag");
        Files.writeString(source.resolve("tracked"), "dirty\n");
        runGit(source, "add", "tracked");
        Files.writeString(source.resolve("untracked"), "not copied\n");
        var status = runGit(source, "status", "--porcelain");
        var snapshot = HostRepoSource.capture(url(source));

        var destination = cloneRepo(source, "container project");

        assertEquals(head, runGit(destination, "rev-parse", "HEAD").strip());
        assertEquals("feature/local", runGit(destination, "branch", "--show-current").strip());
        assertEquals(snapshot.refs(), runGit(destination, "for-each-ref", "--format=%(refname) %(objectname) %(symref)"));
        assertEquals(runGit(source, "config", "--null", "--get-regexp", "^(remote\\.|branch\\.)"),
                runGit(destination, "config", "--null", "--get-regexp", "^(remote\\.|branch\\.)"));
        assertEquals("committed\n", Files.readString(destination.resolve("tracked")));
        assertFalse(Files.exists(destination.resolve("untracked")));
        assertEquals("", runGit(destination, "status", "--porcelain"));
        assertEquals(status, runGit(source, "status", "--porcelain"));
        assertFalse(Files.exists(destination.resolve(".git/objects/info/alternates")));
        assertFalse(Files.isSameFile(source.resolve(".git/objects/" + head.substring(0, 2) + "/" + head.substring(2)),
                destination.resolve(".git/objects/" + head.substring(0, 2) + "/" + head.substring(2))));
        Files.move(source, tempDir.resolve("unavailable-source"));
        runGit(destination, "fsck", "--full");
    }

    @Test
    void preservesDetachedHeadThatIsNotReachableFromABranch() throws Exception {
        var source = repository("detached");
        runGit(source, "checkout", "--detach");
        runGit(source, "commit", "--allow-empty", "-m", "Detached commit");
        var destination = cloneRepo(source, "detached-copy");
        assertEquals(runGit(source, "rev-parse", "HEAD"), runGit(destination, "rev-parse", "HEAD"));
        assertEquals("", runGit(destination, "branch", "--show-current"));
        assertEquals("", runGit(destination, "remote"));
    }

    @Test
    void preservesUnbornBranchAndEmptyRepository() throws Exception {
        var source = Files.createDirectories(tempDir.resolve("empty"));
        runGit(source, "init", "-b", "not-main");
        var destination = cloneRepo(source, "empty-copy");
        assertEquals("not-main", runGit(destination, "branch", "--show-current").strip());
        assertEquals("", runGit(destination, "remote"));
    }

    @Test
    void readsRemoteConfigIncludes() throws Exception {
        var source = repository("includes");
        Files.writeString(source.resolve(".git/remotes.cfg"), "[remote \"upstream\"]\n\turl = https://invalid.example/repo.git\n\tfetch = +refs/heads/*:refs/remotes/upstream/*\n\tskipDefaultUpdate\n");
        runGit(source, "config", "include.path", "remotes.cfg");
        var destination = cloneRepo(source, "includes-copy");
        assertEquals("https://invalid.example/repo.git", runGit(destination, "remote", "get-url", "upstream").strip());
        assertEquals("true", runGit(destination, "config", "--bool", "remote.upstream.skipDefaultUpdate").strip());
        assertFalse(Files.exists(destination.resolve(".git/remotes.cfg")));
    }

    @Test
    void parsesFileUrisWithoutLosingPathCaseOrEscaping() {
        var source = tempDir.resolve("Some Project #1/.git");
        var url = source.toUri().toString();
        assertEquals(source, HostRepoSource.path(url));
        assertEquals("Some Project #1", GitRemoteUtils.repoNameFromUrl(url));
        assertFalse(GitRemoteUtils.urlsMatch("file:///host/Repo/.git", "file:///host/repo/.git"));
        for (var invalid : List.of("file://remote/repo/.git", "file:///repo/.git?x", "file:///repo/.git#x")) {
            assertThrows(IllegalArgumentException.class, () -> HostRepoSource.path(invalid));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file://{absolute}", "file:{absolute}", "{absolute}",
            "file://~/templates/repo", "file:~/templates/repo", "~/templates/repo",
            "file://repo", "file:repo", "file://./repo", "file:./repo", "repo", "./repo",
            "file:dir/repo", "file://./dir/repo", "file:./dir/repo", "dir/repo", "./dir/repo"
    })
    void clonesLocalFormsFromTheirDefiningTemplate(String form) throws Exception {
        var originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            var relative = form.contains("dir/repo") ? "dir/repo" : "repo";
            var source = repository("templates/" + relative);
            var template = tempDir.resolve("templates/image.yaml");
            var url = form.replace("{absolute}", source.toString());
            // A bare file://name is the single-component shorthand, not a host/path URL.
            for (var suffix : form.equals("file://repo") ? List.of("") : List.of("", "/.git")) {
                Files.writeString(template, "name: tpl-local\nrepos:\n  - url: '" + url + suffix + "'\n");
                var image = ImageDef.parseFile(template);
                var repo = image.getRepos().getFirst();
                assertEquals("~/repo", repo.getPath());
                assertTrue(HostRepoSource.isHostOnly(repo.getUrl()));
                var snapshot = HostRepoSource.capture(repo.getUrl(), repo.getTemplateDirectory());
                assertEquals(source.resolve(".git"), snapshot.gitDirectory());
                var destination = tempDir.resolve("copy-" + (suffix.isEmpty() ? "worktree" : "gitdir"));
                Files.writeString(source.resolve("tracked"), "dirty\n");
                Files.writeString(source.resolve("untracked"), "private\n");
                shell(snapshot.cloneScript(snapshot.gitDirectory().toString(), destination.toString()));
                assertEquals("committed\n", Files.readString(destination.resolve("tracked")));
                assertFalse(Files.exists(destination.resolve("untracked")));
                assertEquals(runGit(source, "rev-parse", "HEAD"), runGit(destination, "rev-parse", "HEAD"));
                assertEquals("true", runGit(destination, "config", HostRepoSource.CONFIG_KEY).strip());

                var stored = new BuildSource(Map.of(image.getName(), image), Map.of(), Map.of(),
                        Map.of(image.getName(), image.getSource()));
                var restored = BuildSource.fromJson(stored.toJson()).getDefinitions().get(image.getName());
                var restoredRepo = restored.getRepos().getFirst();
                assertEquals(snapshot, HostRepoSource.capture(restoredRepo.getUrl(), restoredRepo.getTemplateDirectory()));
                var before = restored.contentFingerprint(Map.of());
                runGit(source, "commit", "--allow-empty", "-m", "Another local commit");
                assertNotEquals(before, restored.contentFingerprint(Map.of()));
            }
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file://files.com/path/to/repo", "file://localhost/repo", "file://host:22/repo",
            "file://user@host/repo", "file:////host/repo", "//host/repo",
            "file:repo?query", "file:repo#fragment",
            "file:repo%00", "file:", "file://", "file:~someone/repo",
            "file:%2Frepo", "file://%2Frepo", "file://host%2Frepo", "file://host%3A22"})
    void rejectsUnsafeOrAmbiguousLocalForms(String url) {
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.path(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file://../dir/repo", "file:../dir/repo", "../dir/repo", "./dir/../repo",
            "file:./%2e%2e/repo", "file://./%2e%2e/repo"})
    void rejectsParentTraversalInTemplateSources(String url) {
        var error = assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture(url, tempDir));
        assertTrue(error.getMessage().contains("must not contain '..'"));
    }

    @Test
    void relativeSourcesNeedATemplateAndCannotEscapeThroughSymlinks() throws Exception {
        var outside = repository("outside");
        var templates = Files.createDirectory(tempDir.resolve("templates"));
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture("repo"));
        Files.createSymbolicLink(templates.resolve("repo"), outside);
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture("repo", templates));
        var inside = Files.createDirectory(templates.resolve("inside"));
        Files.createSymbolicLink(inside.resolve(".git"), outside.resolve(".git"));
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture("inside", templates));
        assertEquals(outside.resolve(".git"), HostRepoSource.capture(outside.toString()).gitDirectory());
    }

    @Test
    void supportsBareRepositoriesWithoutSearchingAncestorDirectories() throws Exception {
        var source = repository("repo");
        var bare = tempDir.resolve("bare.git");
        runGit(tempDir, "clone", "--bare", source.toString(), bare.toString());
        assertEquals(HostRepoSource.capture(source.toString()).head(), HostRepoSource.capture(bare.toString()).head());
        var subdir = Files.createDirectory(source.resolve("subdir"));
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture(subdir.toString()));
    }

    @Test
    void inheritedRepositoriesKeepTheirOwnTemplateDirectory() throws Exception {
        var parentRepo = repository("parent/repo");
        var childRepo = repository("child/repo");
        runGit(childRepo, "commit", "--allow-empty", "-m", "Different child HEAD");
        var parentFile = tempDir.resolve("parent/image.yaml");
        var childFile = tempDir.resolve("child/image.yaml");
        Files.writeString(parentFile, "name: tpl-parent\nrepos:\n  - url: repo\n");
        Files.writeString(childFile, "name: tpl-child\nparent: tpl-parent\nrepos:\n  - url: repo\n");
        var parent = ImageDef.parseFile(parentFile);
        var child = ImageDef.parseFile(childFile);
        var definitions = Map.of(parent.getName(), parent, child.getName(), child);
        var stored = new BuildSource(definitions, Map.of(), Map.of(),
                Map.of(parent.getName(), parent.getSource(), child.getName(), child.getSource()));
        var restored = BuildSource.fromJson(stored.toJson()).getDefinitions();
        var repos = HostRepoRefresh.collectAllRepos(restored.get(child.getName()), restored);
        assertEquals(List.of(childRepo.resolve(".git"), parentRepo.resolve(".git")), repos.stream()
                .map(repo -> HostRepoSource.gitDirectory(repo.getUrl(), repo.getTemplateDirectory())).toList());
    }

    @Test
    void plainPathsKeepLiteralReservedCharactersAndRemoteProtocolsStayRemote() throws Exception {
        var source = repository("Some Project #1%+?");
        assertEquals(HostRepoSource.capture(source.toUri().toString()), HostRepoSource.capture(source.toString()));
        for (var remote : List.of("https://github.com/org/repo.git", "ssh://git@example.com/repo",
                "git@example.com:org/repo", "example.com:org/repo", "git://example.com/repo", "isx://branch/~/repo")) {
            assertFalse(HostRepoSource.isHostOnly(remote), remote);
        }
    }

    @Test
    void rejectsMissingWorktreeAndExternalObjectSources() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture("file:///nonexistent/.git"));
        var source = repository("source");
        Files.writeString(source.resolve(".git/objects/info/alternates"), "/missing/objects\n");
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture(url(source)));
        Files.delete(source.resolve(".git/objects/info/alternates"));
        runGit(source, "config", "remote.origin.promisor", "true");
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture(url(source)));
        runGit(source, "config", "--unset", "remote.origin.promisor");
        runGit(source, "worktree", "add", tempDir.resolve("worktree").toString());
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture(url(tempDir.resolve("worktree"))));
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture(tempDir.resolve("worktree").toString()));
    }

    @Test
    void fingerprintTracksHeadBranchRefsAndRemotesButNotDirtyFiles() throws Exception {
        var source = repository("fingerprint");
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url(source));
        assertEquals("~/fingerprint", repo.getPath());
        var image = new ImageDef();
        image.setRepos(List.of(repo));
        var snapshot = HostRepoSource.capture(url(source));
        var initial = image.contentFingerprint(Map.of());
        Files.writeString(source.resolve("tracked"), "dirty\n");
        assertEquals(initial, image.contentFingerprint(Map.of()));
        runGit(source, "commit", "--allow-empty", "-m", "Another commit");
        var newHead = image.contentFingerprint(Map.of());
        assertNotEquals(initial, newHead);
        assertEquals(initial, image.contentFingerprint(Map.of(), Map.of(url(source), snapshot.fingerprint())));
        runGit(source, "checkout", "-b", "another-branch");
        var newBranch = image.contentFingerprint(Map.of());
        assertNotEquals(newHead, newBranch);
        runGit(source, "remote", "add", "origin", "https://invalid.example/repo");
        assertNotEquals(newBranch, image.contentFingerprint(Map.of()));
        Files.move(source, tempDir.resolve("moved"));
        assertNotEquals(initial, image.contentFingerprint(Map.of()));
    }

    @Test
    void automaticMaintenanceDoesNotFetchHostOnlyRepositories() throws Exception {
        var source = repository("maintenance");
        runGit(source, "remote", "add", "origin", "https://invalid.example/repo");
        var destination = cloneRepo(source, "maintenance-copy");
        var script = GitRemoteUtils.automaticFetchScript().replace("~/*/", "'" + destination + "/'");
        shell(script);
        assertFalse(Files.exists(destination.resolve(".git/FETCH_HEAD")));

        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url(source));
        var config = new SpawnConfig();
        runGit(source, "remote", "add", "local-source", url(source));
        config.setHostPaths(List.of(tempDir.toString()));
        config.setRepoPaths(Map.of("maintenance", source.toString()));
        var messages = new java.util.ArrayList<String>();
        HostRepoRefresh.refresh(List.of(repo), config, true, messages::add);
        assertTrue(messages.isEmpty());
        assertFalse(Files.exists(source.resolve(".git/FETCH_HEAD")));
    }
}
