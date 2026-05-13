package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Joiner;
import hudson.model.Executor;
import hudson.model.Result;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests demonstrating how to avoid executor starvation when using lockable
 * resources in declarative pipelines.
 *
 * <p>When a pipeline uses {@code agent any} (or a specific label) at the
 * top level together with {@code options { lock(...) }}, the build allocates
 * an executor <em>before</em> acquiring the lock. If the resource is busy the
 * build sits on the executor doing nothing — blocking other jobs from using
 * it.
 *
 * <p>The recommended pattern is to use {@code agent none} at the pipeline
 * level and move the {@code agent} directive into individual stages, alongside
 * the {@code lock} option. This way no executor is consumed while waiting for
 * the lock.
 *
 * @see <a href="https://github.com/jenkinsci/lockable-resources-plugin/issues/697">#697</a>
 */
@WithJenkins
class DeclarativePipelineExecutorStarvationTest {

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    // -----------------------------------------------------------------------
    // Best practice: agent none + stage-level agent + lock
    // -----------------------------------------------------------------------

    /**
     * With {@code agent none} at the pipeline level and the lock acquired at
     * stage level, no executor is held while waiting for the resource. A second
     * job that does <em>not</em> need the lock can run immediately.
     */
    @Test
    @Issue("JENKINS-67083")
    void agentNoneWithStageLevelLockDoesNotConsumeExecutorWhileWaiting(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("shared-resource");

        // p1 acquires the lock and holds it via a semaphore
        WorkflowJob p1 = j.createProject(WorkflowJob.class, "holder");
        p1.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        "  agent none",
                        "  stages {",
                        "    stage('work') {",
                        "      options {",
                        "        lock resource: 'shared-resource'",
                        "      }",
                        "      agent any",
                        "      steps {",
                        "        semaphore 'hold-lock'",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold-lock/1", b1);

        // p2 also wants the lock — it should wait WITHOUT consuming an executor
        WorkflowJob p2 = j.createProject(WorkflowJob.class, "waiter");
        p2.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        "  agent none",
                        "  stages {",
                        "    stage('work') {",
                        "      options {",
                        "        lock resource: 'shared-resource'",
                        "      }",
                        "      agent any",
                        "      steps {",
                        "        echo 'waiter got the lock'",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: shared-resource] is not free, waiting for execution", b2);

        // b2 is waiting for the lock — it uses only a flyweight (CPS) executor,
        // not a real build executor, so other jobs can still run.
        assertFlyweightOrNoExecutor(b2);

        // An unrelated job must be able to run while b2 waits
        WorkflowJob p3 = j.createProject(WorkflowJob.class, "unrelated");
        p3.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        "  agent any",
                        "  stages {",
                        "    stage('work') {",
                        "      steps {",
                        "        echo 'unrelated job ran'",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));
        j.assertBuildStatusSuccess(p3.scheduleBuild2(0));

        // Release the lock holder
        SemaphoreStep.success("hold-lock/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertLogContains("waiter got the lock", b2);
    }

    /**
     * Same pattern with a label-based lock: {@code agent none} at pipeline
     * level, stage-level lock + agent. Waiter does not block an executor.
     */
    @Test
    @Issue("JENKINS-67083")
    void agentNoneWithStageLevelLabelLockDoesNotConsumeExecutor(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("board1", "hw");

        WorkflowJob p1 = j.createProject(WorkflowJob.class, "holder");
        p1.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        "  agent none",
                        "  stages {",
                        "    stage('work') {",
                        "      options {",
                        "        lock label: 'hw', resource: null, quantity: 1",
                        "      }",
                        "      agent any",
                        "      steps {",
                        "        semaphore 'hold-lock'",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold-lock/1", b1);

        WorkflowJob p2 = j.createProject(WorkflowJob.class, "waiter");
        p2.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        "  agent none",
                        "  stages {",
                        "    stage('work') {",
                        "      options {",
                        "        lock label: 'hw', resource: null, quantity: 1",
                        "      }",
                        "      agent any",
                        "      steps {",
                        "        echo 'waiter acquired hw lock'",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Label: hw, Quantity: 1] is not free, waiting for execution", b2);
        assertFlyweightOrNoExecutor(b2);

        SemaphoreStep.success("hold-lock/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertLogContains("waiter acquired hw lock", b2);
    }

    /**
     * Demonstrates that preparation stages run without the lock, and the lock
     * is only acquired for the stages that need it (no executor held during
     * the wait).
     */
    @Test
    @Issue("JENKINS-67083")
    void preparationStageRunsWithoutLock(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("deploy-target");

        WorkflowJob p = j.createProject(WorkflowJob.class, "efficient");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        "  agent none",
                        "  stages {",
                        "    stage('Build') {",
                        "      agent any",
                        "      steps {",
                        "        echo 'building without lock'",
                        "      }",
                        "    }",
                        "    stage('Deploy') {",
                        "      options {",
                        "        lock resource: 'deploy-target'",
                        "      }",
                        "      agent any",
                        "      steps {",
                        "        echo 'deploying with lock'",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("building without lock", b);
        j.assertLogContains("deploying with lock", b);
        j.assertLogContains("Lock acquired on [Resource: deploy-target]", b);
    }

    /**
     * Wrapping multiple stages in a parent stage with {@code options { lock }}
     * and {@code agent} holds the lock across all nested stages but still does
     * not block an executor while waiting.
     */
    @Test
    @Issue("JENKINS-67083")
    void nestedStagesShareLockWithoutExecutorStarvation(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("env-resource");

        // holder keeps the lock busy
        WorkflowJob holder = j.createProject(WorkflowJob.class, "holder");
        holder.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        "  agent none",
                        "  stages {",
                        "    stage('hold') {",
                        "      options {",
                        "        lock resource: 'env-resource'",
                        "      }",
                        "      agent any",
                        "      steps {",
                        "        semaphore 'hold-lock'",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));
        WorkflowRun bHolder = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold-lock/1", bHolder);

        // waiter uses nested stages
        WorkflowJob waiter = j.createProject(WorkflowJob.class, "waiter");
        waiter.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        "  agent none",
                        "  stages {",
                        "    stage('Deploy and Test') {",
                        "      options {",
                        "        lock resource: 'env-resource'",
                        "      }",
                        "      agent any",
                        "      stages {",
                        "        stage('Deploy') {",
                        "          steps {",
                        "            echo 'deploying'",
                        "          }",
                        "        }",
                        "        stage('Integration Test') {",
                        "          steps {",
                        "            echo 'testing'",
                        "          }",
                        "        }",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));
        WorkflowRun bWaiter = waiter.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: env-resource] is not free, waiting for execution", bWaiter);
        assertFlyweightOrNoExecutor(bWaiter);

        SemaphoreStep.success("hold-lock/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(bHolder));
        j.assertBuildStatusSuccess(j.waitForCompletion(bWaiter));
        j.assertLogContains("deploying", bWaiter);
        j.assertLogContains("testing", bWaiter);
    }

    /**
     * Asserts that the build either has no executor or only a flyweight
     * executor (number&nbsp;{@code -1}). A flyweight executor runs the CPS
     * engine and does <em>not</em> consume a build-executor slot.
     */
    private static void assertFlyweightOrNoExecutor(WorkflowRun run) {
        Executor exec = run.getExecutor();
        assertTrue(
                exec == null || exec.getNumber() == -1,
                "Build should only use a flyweight executor while waiting for a lock, "
                        + "but holds heavyweight executor #"
                        + (exec == null ? "null" : exec.getNumber()));
    }

    private static String m(String... lines) {
        return Joiner.on('\n').join(lines);
    }
}
