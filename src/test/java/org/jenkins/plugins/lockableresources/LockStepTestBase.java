package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

public class LockStepTestBase {

    protected static void isPaused(WorkflowRun run, int count, int effectivePauses) {
        int pauseActions = 0, pausedActions = 0;
        for (FlowNode node : new FlowGraphWalker(run.getExecution())) {
            for (PauseAction pauseAction : PauseAction.getPauseActions(node)) {
                ++pauseActions;
                if (pauseAction.isPaused()) {
                    ++pausedActions;
                }
            }
        }
        assertEquals(count, pauseActions);
        assertEquals(effectivePauses, pausedActions);
    }
}
