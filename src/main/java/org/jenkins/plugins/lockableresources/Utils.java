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
import hudson.Util;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Cause;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.triggers.SCMTrigger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.jobParameter.LockableResourcesParameterValue;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkinsci.plugins.workflow.steps.StepContext;

public abstract class Utils {
    @Nonnull
    public static Set<String> splitLabels(@Nullable String label) {
        Set<String> res = new HashSet<>();
        if(label != null) {
            label = label.trim();
            if(label.startsWith(LockableResource.GROOVY_LABEL_MARKER)) {
                res.add(label);
            } else if(!label.isEmpty()) {
                res.addAll(Arrays.asList(label.split("[\\s,]+\\s*")));
            }
        }
        return res;
    }

    @CheckForNull
    public static Job getProject(@Nonnull Queue.Item item) {
        if(item.task instanceof Job) {
            return (Job) item.task;
        }
        return null;
    }

    @Nonnull
    public static Job<?, ?> getProject(@Nonnull Run<?, ?> build) {
        return build.getParent();
    }

    @Nonnull
    private static Map<String, String> getParameters(@Nonnull Run<?, ?> run) {
        List<ParametersAction> paramsActions = run.getActions(ParametersAction.class);
        return getParameters(paramsActions, getProject(run));
    }

    @Nonnull
    private static Map<String, String> getParameters(@Nonnull Queue.Item item) {
        List<ParametersAction> paramsActions = item.getActions(ParametersAction.class);
        return getParameters(paramsActions, getProject(item));
    }

    @Nonnull
    private static Map<String, String> getParameters(@Nonnull List<ParametersAction> paramsActions, @CheckForNull Job<?, ?> job) {
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
    @Nonnull
    public static EnvVars getEnvVars(@Nullable Run<?, ?> run, @Nullable TaskListener listener) {
        if(run == null) {
            return new EnvVars();
        }
        Map<String, String> params = getParameters(run);
        try {
            if(listener != null) {
                EnvVars res = run.getEnvironment(listener);
                res.overrideAll(params);
                return res;
            }
        } catch(IOException | InterruptedException e) {
        }
        return new EnvVars(params);
    }

    @Nonnull
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

    @Nonnull
    public static EnvVars getEnvVars(@Nonnull Queue.Item item) {
        Map<String, String> params = getParameters(item);
        return new EnvVars(params);
    }

    @Nonnull
    public static String getParameterValue(@Nonnull Collection<LockableResource> resources) {
        ArrayList<String> resourcesNames = new ArrayList<>(resources.size());
        for(LockableResource r : resources) {
            resourcesNames.add(r.getName());
        }
        Collections.sort(resourcesNames);
        StringBuilder lbl = new StringBuilder();
        for(String name : resourcesNames) {
            if(lbl.length() > 0) {
                lbl.append(", ");
            }
            lbl.append(name);
        }
        return lbl.toString();
    }

    @CheckForNull
    public static String getUserId() {
        User user = User.current();

        if(user != null) {
            return user.getId();
        } else {
            return null;
        }
    }

    @CheckForNull
    public static String getUserId(@Nullable String userName) {
        String myName = Util.fixEmptyAndTrim(userName);
        if(myName == null) {
            return null;
        }
        User user = User.get(myName, false, new HashMap());

        if(user != null) {
            return user.getId();
        } else {
            return myName;
        }
    }

    @CheckForNull
    public static String getUserName(@Nullable String userId) {
        String myId = Util.fixEmptyAndTrim(userId);
        if(myId == null) {
            return null;
        }
        User user = User.getById(myId, false);

        if(user != null) {
            return user.getFullName();
        } else {
            return myId;
        }
    }

    @CheckForNull
    public static String getUserId(@Nullable Run<?, ?> build) {
        if(build == null) {
            return null;
        }
        return getUserId(build.getCauses());
    }

    @CheckForNull
    public static String getUserId(@Nullable Queue.Item item) {
        if(item == null) {
            return null;
        }
        return getUserId(item.getCauses());
    }
    
    @CheckForNull
    private static String getUserId(@Nonnull Collection<Cause> causes) {
        String defaultResult = null;
        for(Cause cause : causes) {
            if(cause instanceof Cause.UpstreamCause) {
                Cause.UpstreamCause upstreamCause = (Cause.UpstreamCause) cause;
                Job job = Jenkins.getInstance().getItemByFullName(upstreamCause.getUpstreamProject(), Job.class);
                if(job != null) {
                    Run upstream = job.getBuildByNumber(upstreamCause.getUpstreamBuild());
                    if(upstream != null) {
                        defaultResult = getUserId(upstream);
                    }
                }
            }
            if((defaultResult == null) && (cause instanceof SCMTrigger.SCMTriggerCause)) {
                defaultResult = "SCM";
            }
            if(cause instanceof Cause.UserIdCause) {
                Cause.UserIdCause userIdCause = (Cause.UserIdCause) cause;
                return userIdCause.getUserId();
            }
        }
        return defaultResult;
    }
}
