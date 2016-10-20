/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.EnvVars;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.jenkins.plugins.lockableresources.jobParameter.LockableResourcesParameterValue;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkinsci.plugins.workflow.steps.StepContext;

public abstract class Utils {
    public static Set<String> splitLabels(@Nullable String label) {
        Set<String> res = new HashSet<>();
        if(label != null) {
            label = label.trim();
            if(label.startsWith(LockableResource.GROOVY_LABEL_MARKER)) {
                res.add(label);
            } else if(!label.isEmpty()) {
                res.addAll(Arrays.asList(label.split("[\\s,]\\s*")));
            }
        }
        return res;
    }

    public static Job getProject(Queue.Item item) {
        if(item.task instanceof Job) {
            return (Job) item.task;
        }
        return null;
    }

    public static Job<?, ?> getProject(Run<?, ?> build) {
        return build.getParent();
    }

    private static Map<String, String> getParameters(Run<?, ?> run) {
        List<ParametersAction> paramsActions = run.getActions(ParametersAction.class);
        return getParameters(paramsActions, getProject(run));
    }

    private static Map<String, String> getParameters(Queue.Item item) {
        List<ParametersAction> paramsActions = item.getActions(ParametersAction.class);
        return getParameters(paramsActions, getProject(item));
    }

    private static Map<String, String> getParameters(List<ParametersAction> paramsActions, Job<?, ?> job) {
        HashMap<String, String> params = new HashMap<>();
        for(ParametersAction pa : paramsActions) {
            if(pa != null) {
                List<ParameterValue> paramsValues = pa.getParameters();
                for(ParameterValue pv : paramsValues) {
                    if(pv != null) {
                        Object value = pv.getValue();
                        if(value == null) {
                            params.put(pv.getName(), "");
                        } else if(value instanceof LockableResourcesParameterValue) {
                            LockableResourcesParameterValue v = (LockableResourcesParameterValue) value;
                            params.put(pv.getName(), v.getEnvString());
                        } else {
                            params.put(pv.getName(), pv.getValue().toString());
                        }
                    }
                }
            }
        }
        if((job != null) && (job instanceof MatrixConfiguration)) {
            MatrixConfiguration matrix = (MatrixConfiguration) job;

            params.putAll(matrix.getCombination());
        }
        return params;
    }

    /**
     * Create environment based on item parameters
     *
     * @param run
     * @param listener
     *
     * @return
     */
    public static EnvVars getEnvVars(@Nullable Run<?, ?> run, @Nullable TaskListener listener) {
        if(run == null) {
            return new EnvVars();
        }
        Map<String, String> params = getParameters(run);
        try {
            if(listener != null & run != null) {
                EnvVars res = run.getEnvironment(listener);
                res.overrideAll(params);
                return res;
            }
        } catch(IOException | InterruptedException e) {
        }
        return new EnvVars(params);
    }

    public static EnvVars getEnvVars(@Nullable StepContext context) {
        Run<?, ?> run = null;
        TaskListener listener = null;
        if(context != null) {
            try {
                run = context.get(Run.class);
            } catch(IOException | InterruptedException e) {
            }
            try {
                listener = context.get(TaskListener.class);
            } catch(IOException | InterruptedException e) {
            }
        }
        return getEnvVars(run, listener);
    }

    public static EnvVars getEnvVars(Queue.Item item) {
        Map<String, String> params = getParameters(item);
        return new EnvVars(params);
    }
}
