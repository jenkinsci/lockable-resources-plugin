package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertEquals;

import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.junit.ClassRule;
import org.jvnet.hudson.test.BuildWatcher;

public class LockStepTestBase {

  @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

  protected void isPaused(WorkflowRun run, int count, int effectivePauses) {
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
