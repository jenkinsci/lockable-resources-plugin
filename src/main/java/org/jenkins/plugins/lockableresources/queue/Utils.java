/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jenkins.plugins.lockableresources.ExcludeFromJacocoGeneratedReport;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

public final class Utils {
    private Utils() {}

    /** Pattern to detect {@code ${...}} variable references in configuration values. */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{[^}]+}");

    @CheckForNull
    public static Job<?, ?> getProject(@NonNull Queue.Item item) {
        if (item.task instanceof Job) return (Job<?, ?>) item.task;
        return null;
    }

    @NonNull
    public static Job<?, ?> getProject(@NonNull Run<?, ?> build) {
        return build.getParent();
    }

    /**
     * Build the required-resources structure for a project, without additional environment variables.
     *
     * @see #requiredResources(Job, EnvVars)
     */
    @Deprecated
    @ExcludeFromJacocoGeneratedReport
    @CheckForNull
    @Restricted(NoExternalUse.class)
    public static LockableResourcesStruct requiredResources(@NonNull Job<?, ?> project) {
        return requiredResources(project, null);
    }

    /**
     * Build the required-resources structure for a project, merging any additional
     * environment variables (e.g.&nbsp;build parameters) into the expansion context.
     *
     * @param project        the job whose {@link RequiredResourcesProperty} is read
     * @param additionalEnv  extra variables to use when expanding {@code ${...}} references;
     *                       may be {@code null}
     * @return the struct, or {@code null} if the project has no lockable-resource property
     */
    @CheckForNull
    @Restricted(NoExternalUse.class)
    public static LockableResourcesStruct requiredResources(
            @NonNull Job<?, ?> project, @CheckForNull EnvVars additionalEnv) {
        EnvVars env = new EnvVars();

        for (var ma : ExtensionList.lookup(MatrixAssist.class)) {
            env.putAll(ma.getCombination(project));
            project = ma.getMainProject(project);
        }

        if (additionalEnv != null) {
            env.putAll(additionalEnv);
        }

        RequiredResourcesProperty property = project.getProperty(RequiredResourcesProperty.class);
        if (property != null) return new LockableResourcesStruct(property, env);

        return null;
    }

    /**
     * Extract build parameters from a {@link Queue.Item} and return them as {@link EnvVars}
     * so that {@code ${PARAM}} references in resource names, labels and numbers are expanded.
     */
    @NonNull
    @Restricted(NoExternalUse.class)
    public static EnvVars getParametersAsEnvVars(@NonNull Queue.Item item) {
        EnvVars env = new EnvVars();
        List<ParametersAction> paramActions = item.getActions(ParametersAction.class);
        for (ParametersAction action : paramActions) {
            if (action == null) continue;
            for (ParameterValue p : action.getParameters()) {
                if (p == null) continue;
                Object value = p.getValue();
                if (value != null) {
                    env.put(p.getName(), value.toString());
                }
            }
        }
        return env;
    }

    /**
     * Returns {@code true} when the given string contains at least one {@code ${...}} variable
     * reference that will be resolved at build time.
     */
    @Restricted(NoExternalUse.class)
    public static boolean containsVariable(@CheckForNull String value) {
        return value != null && VARIABLE_PATTERN.matcher(value).find();
    }

    public interface MatrixAssist {
        @NonNull
        Map<String, String> getCombination(@NonNull Job<?, ?> project);

        @NonNull
        Job<?, ?> getMainProject(@NonNull Job<?, ?> project);
    }

    @OptionalExtension(requirePlugins = "matrix-project")
    public static final class MatrixImpl implements MatrixAssist {
        @Override
        public Map<String, String> getCombination(Job<?, ?> project) {
            return project instanceof MatrixConfiguration mc ? mc.getCombination() : Map.of();
        }

        @Override
        public Job<?, ?> getMainProject(Job<?, ?> project) {
            return project instanceof MatrixConfiguration mc ? mc.getParent() : project;
        }
    }
}
