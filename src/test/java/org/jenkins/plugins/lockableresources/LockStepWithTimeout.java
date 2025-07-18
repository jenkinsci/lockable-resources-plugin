package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepWithTimeout extends LockStepTestBase {

    private JenkinsRule jenkinsRule;

    @BeforeEach
    void setUp(JenkinsRule jenkinsRule) {
        this.jenkinsRule = jenkinsRule;
    }

    @Test
    void lockWithTimeoutInside(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("Resource1", "label1");
        lrm.createResourceWithLabel("Resource2", "label2");

        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                def stages = [:];
                        stages['stage1'] = {
                            echo 'Start stage 1';
                            lock('Resource1') {
                                echo 'Resource1 locked in stage 1.'
                                lock('Resource2') {
                                    echo 'Resource2 locked in stage 1.'
                                }
                                echo 'Exiting timeout block in stage 1.'
                            }
                            echo 'Nothing locked in stage 1.'
                        }
                        stages['stage2'] = {
                            echo 'Start stage 2';
                            sleep 500; // Simulate some work
                            lock('Resource1') {
                                echo 'Resource1 locked in stage 2.'
                                lock('Resource2') {
                                    echo 'Resource2 locked in stage 2.'
                                }
                                echo 'Exiting timeout block in stage 2.'
                            }
                            echo 'Nothing locked in stage 2.'
                        }
                        stages['stage3'] = {
                            echo 'Start stage 3';
                            sleep 1000; // Simulate some work
                            lock('Resource1') {
                                echo 'Resource1 locked in stage 3.'
                                lock('Resource2') {
                                    echo 'Resource2 locked in stage 3.'
                                }
                                echo 'Exiting timeout block in stage 3.'
                            }
                            echo 'Nothing locked in stage 3.'
                        }

                    timeout(time: 5, unit: 'SECONDS') {
                        echo 'Start timeout block.'
                        parallel stages;
                        echo 'Exiting timeout block.'
                    }
                    echo 'Finish'""",
                true));

        // at first I will just start the build and wait for it to complete. No resources are locked or reserved before.
        // this shall be more or less a smoke test to see if the code runs without exceptions.
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Start timeout block.", b1);
        j.assertLogContains("Start stage 1", b1);
        j.assertLogContains("Resource1 locked in stage 1.", b1);
        j.assertLogContains("Resource2 locked in stage 1.", b1);
        j.assertLogContains("Start stage 2", b1);
        j.assertLogContains("Resource1 locked in stage 2.", b1);
        j.assertLogContains("Resource2 locked in stage 2.", b1);
        j.assertLogContains("Start stage 3", b1);
        j.assertLogContains("Resource1 locked in stage 3.", b1);
        j.assertLogContains("Resource2 locked in stage 3.", b1);
        j.assertLogContains("Exiting timeout block.", b1);
        j.assertLogContains("Finish", b1);
        assertNotNull(lrm.fromName("Resource1"));
        assertTrue(lrm.fromName("Resource1").isFree());
        assertNotNull(lrm.fromName("Resource2"));
        assertTrue(lrm.fromName("Resource2").isFree());

        // now I will start the build again, but this time I will reserve the resources before.
        lrm.fromName("Resource1").reserve("by user 1");
        // the build should now wait for the resource to be released, but the timeout should be triggered and the build
        // should fail.
        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b2));
        j.assertLogContains("Start timeout block.", b2);
        j.assertLogContains("Start stage 1", b2);
        j.assertLogNotContains("Resource1 locked in stage 1.", b2);
        j.assertLogNotContains("Resource2 locked in stage 1.", b2);
        j.assertLogContains("Start stage 2", b2);
        j.assertLogNotContains("Resource1 locked in stage 2.", b2);
        j.assertLogNotContains("Resource2 locked in stage 2.", b2);
        j.assertLogContains("Start stage 3", b2);
        j.assertLogNotContains("Resource1 locked in stage 3.", b2);
        j.assertLogNotContains("Resource2 locked in stage 3.", b2);
        j.assertLogNotContains("Exiting timeout block.", b2);
        j.assertLogContains("Finish", b2);
        assertNotNull(lrm.fromName("Resource1"));
        assertTrue(lrm.fromName("Resource1").isReserved());
        assertNotNull(lrm.fromName("Resource2"));
        assertTrue(lrm.fromName("Resource2").isFree());

        // now I will release the Resource1 and reserve Resource2 and start the build again.
        // the Resource2 will be 1 times reserved and 0 times locked and 3 times in the queue.
        // the Resource1 will be 0 times reserved and 1 times locked and 2 times in the queue.
        // the timeout should be triggered and the build should fail.
        // the Resource1 must be free and the Resource2 must be reserved.
        // The scope of queued stages can shall not be executed (because of abort signal by timeout).
        lrm.unreserve(Collections.singletonList(lrm.fromName("Resource1")));
        lrm.fromName("Resource2").reserve("by user 2");
        WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b3));
        j.assertLogContains("Start timeout block.", b3);
        j.assertLogContains("Start stage 1", b3);
        j.assertLogContains("Resource1 locked in stage 1.", b3);
        j.assertLogNotContains("Resource2 locked in stage 1.", b3);
        j.assertLogContains("Start stage 2", b3);
        j.assertLogNotContains("Resource1 locked in stage 2.", b3);
        j.assertLogNotContains("Resource2 locked in stage 2.", b3);
        j.assertLogContains("Start stage 3", b3);
        j.assertLogNotContains("Resource1 locked in stage 3.", b3);
        j.assertLogNotContains("Resource2 locked in stage 3.", b3);
        j.assertLogNotContains("Exiting timeout block.", b3);
        j.assertLogContains("Finish", b3);
        assertNotNull(lrm.fromName("Resource1"));
        assertTrue(lrm.fromName("Resource1").isFree());
        assertNotNull(lrm.fromName("Resource2"));
        assertTrue(lrm.fromName("Resource2").isReserved());
    }
}
