package org.jenkins.plugins.lockableresources;

import hudson.Launcher;
import hudson.model.*;
import hudson.model.labels.LabelExpression;
import hudson.model.queue.FutureImpl;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestBuilder;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class UtilFn {
  // Below are utility functions to help with some of the repetitive setup and running of the tests
  static WorkflowJob createWaitingForResourcesJob(RestartableJenkinsRule story,
                                                  String lockParameters,
                                                  String jobName) throws Throwable {
    // Create job definition that tries to acquire a lock and then wait on a semaphore
    WorkflowJob resourceJob = story.j.jenkins.createProject(WorkflowJob.class, jobName);
    resourceJob.setDefinition(new CpsFlowDefinition(
            "lock(" + lockParameters + ") {\n" +
                         // surface the selected resources in the build displayName if using RESOURCE_VAR
                    "	 if (env.RESOURCE_VAR != null) { \n" +
                    "	     currentBuild.displayName += \" (${env.RESOURCE_VAR})\" \n" +
                    "	     echo \"Got resource (${env.RESOURCE_VAR})\"\n" +
                    "	 }\n" +
                    "	 semaphore 'wait-inside'\n" +
                    "}\n" +
                    "echo 'Finish'"
    ));
    return resourceJob;
  }

  // Finds any build resources that have been put into the build name
  public static String getBuildResource(WorkflowRun run) {
    String buildName = run.getFullDisplayName();
    return buildName.substring(buildName.indexOf('(')+1, buildName.lastIndexOf(')'));
  }

  static WorkflowRun scheduleBuildAndCheckWaiting(RestartableJenkinsRule story,
                                                  WorkflowJob job,
                                                  String lockMessage,
                                                  int correctAmount,
                                                  int available) throws Throwable {
    // schedule a build for which it is expected there aren't enough resources to start and the build will wait
    WorkflowRun build = job.scheduleBuild2(0).waitForStart();
    story.j.waitForMessage("Trying to acquire lock on [" + lockMessage + "]", build);
    story.j.waitForMessage("Found " + available + " available resource(s). Waiting for correct amount: " + correctAmount + ".", build);
    return build;
  }

  static Queue.Item scheduleBuildAndCheckWaiting(RestartableJenkinsRule story,
                                                      FreeStyleProject job,
                                                      String lockMessage,
                                                      int correctAmount,
                                                      int available) throws Throwable {
    // schedule a build for which it is expected there aren't enough resources to start and the build will wait
    job.scheduleBuild2(0);
    Queue.Item queueItem = null;
    // check the item is queued and blocked
    while(queueItem == null || !(queueItem.task == job && queueItem.isBlocked())) {
      try {
        queueItem = story.j.jenkins.getQueue().getItems()[0];
      } catch (ArrayIndexOutOfBoundsException e) {
        // ignore this, there might be nothing in the queue and it might be moving fast in a different thread,
        // so can't do a check first
      }
      Thread.sleep(1000);
    }
    //TODO Sue add this check
//    assert(queueItem.getWhy().contains(lockMessage));
    return queueItem;
  }

  static int acquireLockAndFinishBuild(RestartableJenkinsRule story,
                                       WorkflowRun build,
                                       String lockMessage,
                                       int stepIndex) throws Throwable{
    // the build should be able to acquire the lock it needs, signal its semaphore and finish successfully
    story.j.waitForMessage("Lock acquired on [" + lockMessage + "]", build);
    SemaphoreStep.success("wait-inside/" + stepIndex++, null);
    story.j.waitForMessage("Lock released on resource [" + lockMessage + "]", build);
    story.j.waitForMessage("Finish", build);
    story.j.assertBuildStatus(Result.SUCCESS, build);

    return stepIndex;
  }

  static FreeStyleProject createWaitingForResourcesFreestyleJob(RestartableJenkinsRule story,
                                                                RequiredResourcesProperty requiredResources,
                                                                String jobName) throws Throwable {
    // Create job definition that tries to acquire a lock and then wait on a semaphore
    FreeStyleProject job = story.j.createFreeStyleProject(jobName);
    job.addProperty(requiredResources);
    return job;
  }

  static void acquireLockAndFinishBuild(RestartableJenkinsRule story,
                                       FreeStyleBuild build,
                                       String lockMessage) throws Throwable{
    // the build should be able to acquire the lock it needs, signal its semaphore and finish successfully
    story.j.waitForMessage("acquired lock on [" + lockMessage + "]", build);
    story.j.waitForMessage("released lock on [" + lockMessage + "]", build);
    story.j.waitForMessage("Finished", build);
    story.j.assertBuildStatus(Result.SUCCESS, build);
  }

  static RequiredResourcesProperty labelResource(String label) {
    return new RequiredResourcesProperty(null, null,
            null, label, null);
  }

  static RequiredResourcesProperty labelResource(String label, int number) {
    return new RequiredResourcesProperty(null, null,
            Integer.toString(number), label, null);
  }

  static RequiredResourcesProperty nameResource(String name) {
    return new RequiredResourcesProperty(name, null, null, null, null);
  }

  static RequiredResourcesProperty labelResourceEnvVar(String label, String varName) {
    return new RequiredResourcesProperty(null, varName, null, label, null);
  }

  static RequiredResourcesProperty groovyResourceEnvVar(String groovyScript, String varName) {
    return new RequiredResourcesProperty(null, varName, null,
            null, new SecureGroovyScript(groovyScript, true, null));
  }
}
