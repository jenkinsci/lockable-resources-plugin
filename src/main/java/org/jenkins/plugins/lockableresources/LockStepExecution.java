package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.EnvVars;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;

import com.google.inject.Inject;

import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;

public class LockStepExecution extends AbstractStepExecutionImpl {

	@Inject(optional = true)
	private LockStep step;

	@StepContextParameter
	private transient Run<?, ?> run;

	@StepContextParameter
	private transient TaskListener listener;

	private static final Logger LOGGER = Logger.getLogger(LockStepExecution.class.getName());

	@Override
	public boolean start() throws Exception {
		step.validate();

		listener.getLogger().println("Trying to acquire lock on [" + step + "]");
		List<String> resources = new ArrayList<String>();
		if (step.resource != null) {
			if (LockableResourcesManager.get().createResource(step.resource)) {
				listener.getLogger().println("Resource [" + step + "] did not exist. Created.");
			}
			resources.add(step.resource);
		}
		LockableResourcesStruct resourceHolder = new LockableResourcesStruct(resources, step.label, step.quantity);
		LockableResourcesManager.get().lockOrQueue(resourceHolder, getContext(), step, listener.getLogger());
		return false;
	}

	public static void proceed(List<String> resourceNames, StepContext context, String resourceDescription, String resourceVariableName) {
		Run<?, ?> r = null;
		String logString;
		try {
			r = context.get(Run.class);
			logString = "Lock acquired on [" + resourceDescription + "] by " + r.getExternalizableId();
            for (String name: resourceNames) {
                if (!resourceDescription.contains(name)) {
                    logString += " Resources " + resourceNames;
                    break;
                }
            }
			context.get(TaskListener.class).getLogger().println(logString);
		} catch (Exception e) {
			context.onFailure(e);
			return;
		}

        LOGGER.finest(logString);

        handleResourceVariable(context, resourceNames, resourceVariableName)
                .withCallback(new Callback(resourceNames, resourceDescription, resourceVariableName))
                .withDisplayName(null)
                .start();
    }

    private static BodyInvoker handleResourceVariable(StepContext context, List<String> resourcenames, String resourceVariableName) {
        BodyInvoker bodyInvoker = context.newBodyInvoker();
        if (resourceVariableName != null) {
            EnvironmentExpander environmentExpander;
            try {
                environmentExpander = EnvironmentExpander.merge(context.get(EnvironmentExpander.class),
                        new ResourceVariableNameExpander(resourceVariableName, resourcenames));
            } catch (Exception e) {
                throw new RuntimeException("adding environment variable for the resource name failed.", e);
            }

            bodyInvoker = bodyInvoker.withContext(environmentExpander);
        }

	    return bodyInvoker;
    }

    private static final class Callback extends BodyExecutionCallback.TailCall {

		private final List<String> resourceNames;
		private final String resourceDescription;
		private final String resourceVariableName;

		Callback(List<String> resourceNames, String resourceDescription, String resourceVariableName) {
			this.resourceNames = resourceNames;
			this.resourceDescription = resourceDescription;
			this.resourceVariableName = resourceVariableName;
		}

		protected void finished(StepContext context) throws Exception {
		    Run build = context.get(Run.class);
			LockableResourcesManager.get().releaseResourceNames(this.resourceNames, build);
			PrintStream taskLogger = context.get(TaskListener.class).getLogger();
            String logString = "Lock released on resource [" + this.resourceDescription + "] by " + build.getExternalizableId();
            for (String name: this.resourceNames) {
                if (!this.resourceDescription.contains(name)) {
                    logString += " Resources " + this.resourceNames;
                    break;
                }
            }
			if (taskLogger != null)
				taskLogger.println(logString);
			LOGGER.finest(logString);
		}

		private static final long serialVersionUID = 1L;

	}

    private static final class ResourceVariableNameExpander extends EnvironmentExpander {
        private static final long serialVersionUID = 1;
        private final Map<String,String> overrides;

        private ResourceVariableNameExpander(String resourceVariableName, List<String> resourceNames) {
            this.overrides = new HashMap<>();
            this.overrides.put(resourceVariableName, joinResourceNames(resourceNames));
        }

        private String joinResourceNames(List<String> resourceNames) {
            StringBuilder resourceName = new StringBuilder();
            for (String res : resourceNames) {
                resourceName.append((resourceName.length() == 0) ? res : ", " + res);
            }
            return resourceName.toString();
        }

        @Override public void expand(EnvVars env) throws IOException, InterruptedException {
            env.overrideAll(overrides);
        }
    }

	@Override
	public void stop(Throwable cause) throws Exception {
		boolean cleaned = LockableResourcesManager.get().unqueueContext(getContext());
		if (!cleaned) {
			LOGGER.log(Level.WARNING, "Cannot remove context from lockable resource waiting list. The context is not in the waiting list.");
		}
		getContext().onFailure(cause);
	}

	private static final long serialVersionUID = 1L;

}
