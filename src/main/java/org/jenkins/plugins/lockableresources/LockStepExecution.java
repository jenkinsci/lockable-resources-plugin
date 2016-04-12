package org.jenkins.plugins.lockableresources;

import java.io.IOException;
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
		if (LockableResourcesManager.get().createResource(step.resource)) {
			listener.getLogger().println("Resource [" + step.resource + "] did not exist. Created.");
		}
		listener.getLogger().println("Trying to acquire lock on [" + step.resource + "]");
		LockableResourcesStruct resourceHolder = new LockableResourcesStruct(step.resource);
		if(!LockableResourcesManager.get().lock(resourceHolder.required, run, getContext(), step.inversePrecedence)) {
			// we have to wait
			listener.getLogger().println("[" + step.resource + "] is locked, waiting...");
		} // proceed is called inside lock otherwise
		return false;
	}

	public static void proceed(StepContext context, String resource, boolean inversePrecedence) {
		LockableResourcesStruct resourceHolder = new LockableResourcesStruct(resource);
		Run<?, ?> r = null;
		try {
			r = context.get(Run.class);
			context.get(TaskListener.class).getLogger().println("Lock acquired on [" + resource + "]");
		} catch (Exception e) {
			context.onFailure(e);
			return;
		}

		LOGGER.finest("Lock acquired on [" + resource + "] by " + r.getExternalizableId());
		context.newBodyInvoker().
			withCallback(new Callback(resourceHolder, inversePrecedence)).
			withDisplayName(null).
			start();
	}

	private static final class Callback extends BodyExecutionCallback.TailCall {

		private final LockableResourcesStruct resourceHolder;
		private final boolean inversePrecedence;

		Callback(LockableResourcesStruct resourceHolder, boolean inversePrecedence) {
			// It's granted to contain one item (and only one for now)
			this.resourceHolder = resourceHolder;
			this.inversePrecedence = inversePrecedence;
		}

		protected void finished(StepContext context) throws Exception {
			LockableResourcesManager.get().unlock(resourceHolder.required, context.get(Run.class), context, inversePrecedence);
			context.get(TaskListener.class).getLogger().println("Lock released on resouce [" + resourceHolder.required.get(0) + "]");
			LOGGER.finest("Lock released on [" + resourceHolder.required.get(0) + "]");
		}

		private static final long serialVersionUID = 1L;

	}

	@Override
	public void stop(Throwable cause) throws Exception {
		boolean cleaned = LockableResourcesManager.get().cleanWaitingContext(LockableResourcesManager.get().fromName(step.resource), getContext());
		if (!cleaned) {
			LOGGER.log(Level.WARNING, "Cannot remove context from lockable resource witing list. The context is not in the waiting list.");
		}
		getContext().onFailure(cause);
	}

	private static final long serialVersionUID = 1L;

}
