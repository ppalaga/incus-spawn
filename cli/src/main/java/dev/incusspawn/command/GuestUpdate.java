package dev.incusspawn.command;

import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;

/**
 * The system update and git fetch steps shared by {@code isx update-all} and
 * {@code isx project update}. Each returns false, after reporting it, when the step failed.
 */
final class GuestUpdate {

    /**
     * Fetches every non-host-only git repository directly under agentuser's home. It is the script itself, not
     * an argument to {@code sh -c}: {@link IncusClient#execInContainer} joins its arguments with
     * spaces into {@code su - -c}, which would leave an unquoted loop that never parses (#1179).
     * Exits non-zero, naming each repository, when any fetch failed. Nothing may prompt (there is
     * nobody to answer), and fetches are quiet so stderr holds only what went wrong.
     */
    static final String GIT_FETCH_SCRIPT = GitRemoteUtils.automaticFetchScript();

    private GuestUpdate() {
    }

    static boolean system(IncusClient incus, String name) {
        BuildOutput.stepStart("Running system updates...");
        return finish(incus.shellExec(name, "dnf", "update", "-y"), "dnf update -y");
    }

    static boolean gitRepos(IncusClient incus, String name) {
        BuildOutput.stepStart("Updating git repositories...");
        return finish(incus.execInContainer(name, "agentuser", GIT_FETCH_SCRIPT), "git fetch");
    }

    /** Ends the step started for {@code what}: done, or failed with its exit code and stderr. */
    static boolean finish(IncusClient.ExecResult result, String what) {
        if (!result.success()) {
            BuildOutput.stepFail(what + " failed (exit code " + result.exitCode() + "): "
                    + result.stderr().strip());
            return false;
        }
        BuildOutput.stepDone();
        return true;
    }
}
