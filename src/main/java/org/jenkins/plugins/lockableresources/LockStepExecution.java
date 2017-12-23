package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;

import com.google.common.base.Joiner;
import com.google.inject.Inject;

import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

public class LockStepExecution extends AbstractStepExecutionImpl {

	private static final Joiner COMMA_JOINER = Joiner.on(',');

    @Inject(optional = true)
	private LockStep step;

	@StepContextParameter
	private transient Run<?, ?> run;

	@StepContextParameter
	private transient TaskListener listener;

	@StepContextParameter
	private transient FlowNode node;

	private static final Logger LOGGER = Logger.getLogger(LockStepExecution.class.getName());

	@Override
	public boolean start() throws Exception {
		step.validate();

		node.addAction(new PauseAction("Lock"));
		listener.getLogger().println("Trying to acquire lock on [" + step + "]");
		
		List<LockableResourcesStruct> resourceHolderList = new ArrayList<>();
		
		for (LockStepResource resource : step.getResources()) {
			List<String> resources = new ArrayList<String>();
			if (resource.resource != null) {
				if (LockableResourcesManager.get().createResource(resource.resource)) {
					listener.getLogger().println("Resource [" + resource + "] did not exist. Created.");
				}
				resources.add(resource.resource);
			}
			resourceHolderList.add(new LockableResourcesStruct(resources, resource.label, resource.quantity));
		}
		
		// determine if there are enough resources available to proceed
		Set<LockableResource> available = LockableResourcesManager.get().checkResourcesAvailability(resourceHolderList, listener.getLogger(), null);
		if (available == null || !LockableResourcesManager.get().lock(available, run, getContext(), step.toString(), step.variable, step.inversePrecedence)) {
			listener.getLogger().println("[" + step + "] is locked, waiting...");
			LockableResourcesManager.get().queueContext(getContext(), resourceHolderList, step.toString());
		} // proceed is called inside lock if execution is possible
		return false;
	}

	public static void proceed(final List<String> resourcenames, StepContext context, String resourceDescription, final String variable, boolean inversePrecedence) {
		Run<?, ?> r = null;
		FlowNode node = null;
		try {
			r = context.get(Run.class);
			node = context.get(FlowNode.class);
			context.get(TaskListener.class).getLogger().println("Lock acquired on [" + resourceDescription + "]");
		} catch (Exception e) {
			context.onFailure(e);
			return;
		}

		LOGGER.finest("Lock acquired on [" + resourceDescription + "] by " + r.getExternalizableId());
		try {
			PauseAction.endCurrentPause(node);
			BodyInvoker bodyInvoker = context.newBodyInvoker().
				withCallback(new Callback(resourcenames, resourceDescription, inversePrecedence)).
				withDisplayName(null);
			if(variable != null && variable.length()>0)
				// set the variable for the duration of the block
				bodyInvoker.withContext(EnvironmentExpander.merge(context.get(EnvironmentExpander.class), new EnvironmentExpander() {
					@Override
					public void expand(EnvVars env) throws IOException, InterruptedException {
						final String resources = COMMA_JOINER.join(resourcenames);
								LOGGER.finest("Setting [" + variable + "] to [" + resources
										+ "] for the duration of the block");
						
						env.override(variable, resources);
					}
				}));
			bodyInvoker.start();
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private static final class Callback extends BodyExecutionCallback.TailCall {

		private final List<String> resourceNames;
		private final String resourceDescription;
		private final boolean inversePrecedence;

		Callback(List<String> resourceNames, String resourceDescription, boolean inversePrecedence) {
			this.resourceNames = resourceNames;
			this.resourceDescription = resourceDescription;
			this.inversePrecedence = inversePrecedence;
		}

		protected void finished(StepContext context) throws Exception {
			LockableResourcesManager.get().unlockNames(this.resourceNames, context.get(Run.class), this.inversePrecedence);
			context.get(TaskListener.class).getLogger().println("Lock released on resource [" + resourceDescription + "]");
			LOGGER.finest("Lock released on [" + resourceDescription + "]");
		}

		private static final long serialVersionUID = 1L;

	}

	@Override
	public void stop(Throwable cause) throws Exception {
		boolean cleaned = LockableResourcesManager.get().unqueueContext(getContext());
		if (!cleaned) {
			LOGGER.log(Level.WARNING, "Cannot remove context from lockable resource witing list. The context is not in the waiting list.");
		}
		getContext().onFailure(cause);
	}

	private static final long serialVersionUID = 1L;

}
