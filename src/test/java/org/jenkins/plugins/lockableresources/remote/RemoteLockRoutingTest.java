/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import java.util.List;
import org.jenkins.plugins.lockableresources.LockStep;
import org.jenkins.plugins.lockableresources.LockStepResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Deciding whether a lock is remote, and whose it is.
 *
 * <p>Everything else about a remote lock happens after these three answers, and getting one wrong is
 * not a visible failure - it is a build quietly locking the wrong controller's resource, or locking
 * locally something it was meant to take from a peer. The delegated-mode override is the sharp edge:
 * a controller configured to delegate sends every serverId-less lock() elsewhere, and overrides a
 * serverId the pipeline did name.
 */
@WithJenkins
class RemoteLockRoutingTest {

    private static LockStep step(String resource, String label, String serverId) {
        LockStep step = new LockStep(resource);
        step.label = label;
        step.serverId = serverId;
        return step;
    }

    @Test
    void aStepIsRemoteWhenItNamesAServerOrTheControllerDelegates(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.setForcedServerId("");

        assertTrue(RemoteLockRouting.isRemoteRequest(step("r", null, "server-a"), lrm), "the DSL named a server");
        assertFalse(RemoteLockRouting.isRemoteRequest(step("r", null, null), lrm), "no server anywhere: local");
        // Blank is not a server name. Treating it as one would send an ordinary local lock over the wire.
        assertFalse(RemoteLockRouting.isRemoteRequest(step("r", null, "   "), lrm), "blank serverId is no serverId");

        lrm.setForcedServerId("server-b");
        assertTrue(
                RemoteLockRouting.isRemoteRequest(step("r", null, null), lrm),
                "delegated mode makes a serverId-less lock remote");
        lrm.setForcedServerId("   ");
        assertFalse(RemoteLockRouting.isRemoteRequest(step("r", null, null), lrm), "blank forcedServerId is not set");

        lrm.setForcedServerId("");
    }

    @Test
    void delegationOverridesTheServerThePipelineNamed(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();

        lrm.setForcedServerId("");
        assertEquals("server-a", RemoteLockRouting.effectiveServerId(step("r", null, "server-a"), lrm, null));

        // The point of delegated mode: the controller's setting wins. A pipeline that names a server
        // still goes where the administrator says, which is why the override is logged.
        lrm.setForcedServerId("server-b");
        assertEquals("server-b", RemoteLockRouting.effectiveServerId(step("r", null, "server-a"), lrm, null));
        assertEquals("server-b", RemoteLockRouting.effectiveServerId(step("r", null, null), lrm, null));
        // Naming the same server as the forced one is agreement, not an override.
        assertEquals("server-b", RemoteLockRouting.effectiveServerId(step("r", null, "server-b"), lrm, null));
        // Surrounding whitespace is not a different server.
        assertEquals("server-b", RemoteLockRouting.effectiveServerId(step("r", null, " server-b "), lrm, null));

        lrm.setForcedServerId("");
    }

    @Test
    void theDisplayTargetNamesWhateverTheStepAskedFor(JenkinsRule j) throws Exception {
        assertEquals("board-1", RemoteLockRouting.displayTarget(step("board-1", null, "server-a")));
        assertEquals("board-1", RemoteLockRouting.displayTarget(step("  board-1  ", null, "server-a")));
        assertEquals("label:hw", RemoteLockRouting.displayTarget(step(null, "hw", "server-a")));
        assertEquals("label:hw", RemoteLockRouting.displayTarget(step(null, "  hw  ", "server-a")));

        // extra on its own is a legitimate request, and has to describe itself somehow.
        LockStep extraOnly = step(null, null, "server-a");
        extraOnly.extra = List.of(new LockStepResource("board-2"));
        assertFalse(RemoteLockRouting.displayTarget(extraOnly).isEmpty());

        // Nothing to lock is not a target. Failing here beats sending an empty request over the wire.
        assertThrows(AbortException.class, () -> RemoteLockRouting.displayTarget(step(null, null, "server-a")));
        assertThrows(AbortException.class, () -> RemoteLockRouting.displayTarget(step("  ", "  ", "server-a")));
    }
}
