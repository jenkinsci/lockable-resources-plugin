/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

  private transient Cache<Long, Date> lastLogged =
    Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();

  static final Logger LOGGER =
    Logger.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

  @Override
  public CauseOfBlockage canRun(Queue.Item item) {
    // Skip locking for multiple configuration projects,
    // only the child jobs will actually lock resources.
    if (item.task instanceof MatrixProject) return null;

    Job<?, ?> project = Utils.getProject(item);
    if (project == null) return null;

    LockableResourcesStruct resources = Utils.requiredResources(project);
    if (resources == null
      || (resources.required.isEmpty()
      && resources.hasLabelFilter() == false
      && resources.getResourceMatchScript() == null)) {
      return null;
    }

    int resourceNumber;
    try {
      resourceNumber = Integer.parseInt(resources.requiredNumber);
    } catch (NumberFormatException e) {
      resourceNumber = 0;
    }

    LOGGER.finest(project.getName() + " trying to get resources with these details: " + resources);

    if (resourceNumber > 0
      || resources.hasLabelFilter()
      || resources.getResourceMatchScript() != null) {
      Map<String, Object> params = new HashMap<>();

      // Inject Build Parameters, if possible and applicable to the "item" type
      try {
        List<ParametersAction> itemparams = item.getActions(ParametersAction.class);
        for (ParametersAction actparam : itemparams) {
          if (actparam == null) continue;
          for (ParameterValue p : actparam.getParameters()) {
            if (p == null) continue;
            params.put(p.getName(), p.getValue());
          }
        }
      } catch (Exception ex) {
        // Report the error and go on with the build -
        // perhaps this item is not a build with args, etc.
        // Note this is likely to fail a bit later in such case.
        if (LOGGER.isLoggable(Level.WARNING)) {
          if (lastLogged.getIfPresent(item.getId()) == null) {
            lastLogged.put(item.getId(), new Date());
            String itemName = project.getFullName() + " (id=" + item.getId() + ")";
            LOGGER.log(Level.WARNING, "Failed to get build params from item " + itemName, ex);
          }
        }
      }

      if (item.task instanceof MatrixConfiguration) {
        MatrixConfiguration matrix = (MatrixConfiguration) item.task;
        params.putAll(matrix.getCombination());
      }

      final List<LockableResource> selected;
      try {
        selected =
          LockableResourcesManager.get()
            .tryQueue(
              resources, item.getId(), project.getFullName(), resourceNumber, params, LOGGER);
      } catch (ExecutionException ex) {
        Throwable toReport = ex.getCause();
        if (toReport == null) { // We care about the cause only
          toReport = ex;
        }
        if (LOGGER.isLoggable(Level.WARNING)) {
          if (lastLogged.getIfPresent(item.getId()) == null) {
            lastLogged.put(item.getId(), new Date());

            String itemName = project.getFullName() + " (id=" + item.getId() + ")";
            LOGGER.log(Level.WARNING, "Failed to queue item " + itemName, toReport.getMessage());
          }
        }

        return new BecauseResourcesQueueFailed(resources, toReport);
      }

      if (selected != null) {
        LOGGER.finest(project.getName() + " reserved resources " + selected);
        return null;
      } else {
        LOGGER.finest(project.getName() + " waiting for resources");
        return new BecauseResourcesLocked(resources);
      }

    } else {
      if (LockableResourcesManager.get()
        .queue(resources.required, item.getId(), project.getFullDisplayName())) {
        LOGGER.finest(project.getName() + " reserved resources " + resources.required);
        return null;
      } else {
        LOGGER.finest(project.getName() + " waiting for resources " + resources.required);
        return new BecauseResourcesLocked(resources);
      }
    }
  }

  public static class BecauseResourcesLocked extends CauseOfBlockage {

    private final LockableResourcesStruct rscStruct;

    public BecauseResourcesLocked(LockableResourcesStruct r) {
      this.rscStruct = r;
    }

    @Override
    public String getShortDescription() {
      if (this.rscStruct.hasLabelFilter() == false) {
        if (!this.rscStruct.required.isEmpty()) {
          return "Waiting for resource instances " + rscStruct.required;
        } else {
          final SecureGroovyScript systemGroovyScript = this.rscStruct.getResourceMatchScript();
          if (systemGroovyScript != null) {
            // Empty or not... just keep the logic in sync
            // with tryQueue() in LockableResourcesManager
            if (systemGroovyScript.getScript().isEmpty()) {
              return "Waiting for resources identified by custom script (which is empty)";
            } else {
              return "Waiting for resources identified by custom script";
            }
          }
          // TODO: Developers should extend here if LockableResourcesStruct is extended
          LOGGER.log(
            Level.WARNING,
            "Failed to classify reason of waiting for resource: " + this.rscStruct);
          return "Waiting for lockable resources";
        }
      } else {
        List<String> rules = new ArrayList<>();
        if (StringUtils.isNotBlank(rscStruct.anyOfLabels))
          rules.add("any of [" + rscStruct.anyOfLabels + "]");
        if (StringUtils.isNotBlank(rscStruct.allOfLabels))
          rules.add("all of [" + rscStruct.allOfLabels + "]");
        if (StringUtils.isNotBlank(rscStruct.noneOfLabels))
          rules.add("none of [" + rscStruct.noneOfLabels + "]");
        return "Waiting for resources with label matching " + rules.stream().collect(Collectors.joining(" and "));
      }
    }
  }

  // Only for UI
  @Restricted(NoExternalUse.class)
  public static class BecauseResourcesQueueFailed extends CauseOfBlockage {

    @NonNull private final LockableResourcesStruct resources;
    @NonNull private final Throwable cause;

    public BecauseResourcesQueueFailed(
      @NonNull LockableResourcesStruct resources, @NonNull Throwable cause) {
      this.cause = cause;
      this.resources = resources;
    }

    @Override
    public String getShortDescription() {
      // TODO: Just a copy-paste from BecauseResourcesLocked, seems strange
      List<String> rules = new ArrayList<>();
      if (StringUtils.isNotBlank(resources.anyOfLabels))
        rules.add("any of [" + resources.anyOfLabels + "]");
      if (StringUtils.isNotBlank(resources.allOfLabels))
        rules.add("all of [" + resources.allOfLabels + "]");
      if (StringUtils.isNotBlank(resources.noneOfLabels))
        rules.add("none of [" + resources.noneOfLabels + "]");

      String resourceInfo =
        rules.isEmpty()
          ? resources.required.toString()
          : "with label matching " + rules.stream().collect(Collectors.joining(" and "));
      return "Execution failed while acquiring the resource "
        + resourceInfo
        + ". "
        + cause.getMessage();
    }
  }
}
