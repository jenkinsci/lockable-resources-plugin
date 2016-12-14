package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;

import com.google.inject.Inject;

import hudson.model.Run;
import hudson.model.TaskListener;

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
		if (step.label != null && !step.label.isEmpty() && step.resource !=  null && !step.resource.isEmpty()) {
			throw new Exception("Label and resource name cannot be specified simultaneously.");
		}
		
		// create resoure only when specified explicitly
		if (step.label == null && LockableResourcesManager.get().createResource(step.resource)) {
			listener.getLogger().println("Resource [" + step + "] did not exist. Created.");
		}
		listener.getLogger().println("Trying to acquire lock on [" + step + "]");
		List<String> resources = new ArrayList<String>();
		resources.add(step.resource);
		LockableResourcesStruct resourceHolder = new LockableResourcesStruct(resources, step.label, step.quantity);
		// determine if there are enough resources available to proceed
		List<LockableResource> available = LockableResourcesManager.get().getAvailableResources(resourceHolder, listener.getLogger(), null);
		if (available == null || !LockableResourcesManager.get().lock(available, run, getContext(), step.toString(), step.inversePrecedence)) {
			listener.getLogger().println("[" + step + "] is locked, waiting...");
			LockableResourcesManager.get().queueContext(getContext(), resourceHolder, step.toString());
		} // proceed is called inside lock if execution is possible
		return false;
	}

	public static void proceed(List<String> resourcenames, StepContext context, String resourceDescription, boolean inversePrecedence) {
		Run<?, ?> r = null;
		try {
			r = context.get(Run.class);
			context.get(TaskListener.class).getLogger().println("Lock acquired on [" + resourceDescription + "]");
		} catch (Exception e) {
			context.onFailure(e);
			return;
		}

		LOGGER.finest("Lock acquired on [" + resourceDescription + "] by " + r.getExternalizableId());
		context.newBodyInvoker().
			withCallback(new Callback(resourcenames, resourceDescription, inversePrecedence)).
			withDisplayName(null).
			start();
	}

	private static final class Callback extends BodyExecutionCallback.TailCall {

		private final List<String> resourcenames;
		private final String resourceDescription;
		private final boolean inversePrecedence;

		Callback(List<String> resourcenames, String resourceDescription, boolean inversePrecedence) {
			this.resourcenames = resourcenames;
			this.resourceDescription = resourceDescription;
			this.inversePrecedence = inversePrecedence;
		}

		protected void finished(StepContext context) throws Exception {
			LockableResourcesManager.get().unlockNames(this.resourcenames, context.get(Run.class), context, this.inversePrecedence);
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
