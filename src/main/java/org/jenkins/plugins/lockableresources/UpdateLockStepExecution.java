/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.model.TaskListener;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/**
 * Execution logic for the {@link UpdateLockStep}.
 *
 * @since TODO
 */
public class UpdateLockStepExecution extends AbstractStepExecutionImpl implements Serializable {

    private static final long serialVersionUID = 1583205294263267002L;
    private static final Logger LOGGER = Logger.getLogger(UpdateLockStepExecution.class.getName());

    private final UpdateLockStep step;

    public UpdateLockStepExecution(UpdateLockStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        step.validate();

        PrintStream logger = getContext().get(TaskListener.class).getLogger();
        LockableResourcesManager lrm = LockableResourcesManager.get();

        synchronized (LockableResourcesManager.syncResources) {
            if (step.isDeleteResource()) {
                handleDeleteResource(lrm, logger);
            } else {
                handleUpdateResource(lrm, logger);
            }
        }

        getContext().onSuccess(null);
        return true;
    }

    /**
     * Handles the deletion of a resource.
     */
    private void handleDeleteResource(LockableResourcesManager lrm, PrintStream logger) {
        String resourceName = step.getResource();
        LockableResource resource = lrm.fromName(resourceName);

        if (resource == null) {
            LockableResourcesManager.printLogs(
                    "Resource [" + resourceName + "] does not exist, nothing to delete.",
                    Level.WARNING,
                    LOGGER,
                    logger);
            return;
        }

        if (resource.isLocked() || resource.isQueued()) {
            LockableResourcesManager.printLogs(
                    "Resource [" + resourceName + "] is currently locked or queued, cannot delete.",
                    Level.WARNING,
                    LOGGER,
                    logger);
            throw new IllegalStateException(Messages.UpdateLockStep_error_resourceInUse(resourceName));
        }

        if (resource.isReserved()) {
            LockableResourcesManager.printLogs(
                    "Resource [" + resourceName + "] is currently reserved, cannot delete.",
                    Level.WARNING,
                    LOGGER,
                    logger);
            throw new IllegalStateException(Messages.UpdateLockStep_error_resourceReserved(resourceName));
        }

        lrm.removeResources(Collections.singletonList(resource));
        lrm.save();

        LockableResourcesManager.printLogs("Resource [" + resourceName + "] deleted.", Level.FINE, LOGGER, logger);
    }

    /**
     * Handles updating an existing resource or creating a new one.
     */
    private void handleUpdateResource(LockableResourcesManager lrm, PrintStream logger) {
        String resourceName = step.getResource();
        LockableResource resource = lrm.fromName(resourceName);

        // Create resource if it doesn't exist and createResource is true
        if (resource == null) {
            if (step.isCreateResource()) {
                lrm.createResource(resourceName);
                resource = lrm.fromName(resourceName);
                if (resource != null) {
                    // Make it persistent (not ephemeral) since it was explicitly created
                    resource.setEphemeral(false);
                    LockableResourcesManager.printLogs(
                            "Resource [" + resourceName + "] created.", Level.FINE, LOGGER, logger);
                }
            } else {
                LockableResourcesManager.printLogs(
                        "Resource [" + resourceName + "] does not exist. Use createResource: true to create it.",
                        Level.WARNING,
                        LOGGER,
                        logger);
                throw new IllegalStateException(Messages.UpdateLockStep_error_resourceNotFound(resourceName));
            }
        }

        if (resource == null) {
            throw new IllegalStateException(Messages.UpdateLockStep_error_resourceNotFound(resourceName));
        }

        // Handle labels
        updateLabels(resource, logger);

        // Handle note
        if (step.getSetNote() != null) {
            resource.setNote(step.getSetNote());
            LockableResourcesManager.printLogs(
                    "Resource [" + resourceName + "] note updated.", Level.FINE, LOGGER, logger);
        }

        lrm.save();
        LockableResourcesManager.printLogs("Resource [" + resourceName + "] updated.", Level.FINE, LOGGER, logger);
    }

    /**
     * Updates labels on the resource based on step configuration.
     */
    private void updateLabels(LockableResource resource, PrintStream logger) {
        String resourceName = resource.getName();

        if (step.getSetLabels() != null) {
            // setLabels replaces all existing labels
            List<String> newLabels = parseLabels(step.getSetLabels());
            resource.setLabels(String.join(" ", newLabels));
            LockableResourcesManager.printLogs(
                    "Resource [" + resourceName + "] labels set to: " + newLabels, Level.FINE, LOGGER, logger);
        } else if (step.getAddLabels() != null || step.getRemoveLabels() != null) {
            // addLabels/removeLabels modify existing labels
            List<String> currentLabels = new ArrayList<>(resource.getLabelsAsList());

            if (step.getAddLabels() != null) {
                List<String> labelsToAdd = parseLabels(step.getAddLabels());
                for (String label : labelsToAdd) {
                    if (!currentLabels.contains(label)) {
                        currentLabels.add(label);
                    }
                }
                LockableResourcesManager.printLogs(
                        "Resource [" + resourceName + "] added labels: " + labelsToAdd, Level.FINE, LOGGER, logger);
            }

            if (step.getRemoveLabels() != null) {
                List<String> labelsToRemove = parseLabels(step.getRemoveLabels());
                currentLabels.removeAll(labelsToRemove);
                LockableResourcesManager.printLogs(
                        "Resource [" + resourceName + "] removed labels: " + labelsToRemove,
                        Level.FINE,
                        LOGGER,
                        logger);
            }

            resource.setLabels(currentLabels.stream().collect(Collectors.joining(" ")));
        }
    }

    /**
     * Parses a whitespace-separated label string into a list of labels.
     */
    private List<String> parseLabels(String labels) {
        if (labels == null || labels.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(labels.trim().split("\\s+"))
                .filter(l -> !l.isEmpty())
                .collect(Collectors.toList());
    }
}
