package org.jenkins.plugins.lockableresources;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;

import com.google.common.collect.Lists;
import com.google.inject.Inject;

import hudson.model.Run;
import hudson.model.TaskListener;

public class LockStepExecution extends AbstractStepExecutionImpl {

	private static final long serialVersionUID = 1L;

	@Inject(optional = true)
	private LockStep step;

	@StepContextParameter
	private transient Run<?, ?> run;

	@StepContextParameter
	private transient TaskListener listener;

	private static final Logger LOGGER = Logger.getLogger(LockStepExecution.class.getName());

	@Override
	public boolean start() throws Exception {
		LockableResourcesManager manager = LockableResourcesManager.get();

		if (manager.createResource(step.resource)) {
			listener.getLogger().println("Resource [" + step.resource + "] did not exist. Created.");
		}

		listener.getLogger().println("Trying to acquire lock on [" + step.resource + "]");

		List<LockableResource> resources = manager.getResourcesWithNames(Lists.newArrayList(step.resource));

		boolean locked = manager.lock(resources, run, getContext(), step.inversePrecedence);

		if (!locked) {
			// we have to wait
			listener.getLogger().println("[" + step.resource + "] is locked, waiting...");
		} // proceed is called inside lock otherwise

		return false;
	}

	@Override
	public void stop(Throwable cause) throws Exception {
		LockableResourcesManager manager = LockableResourcesManager.get();

		boolean cleaned = manager.cleanWaitingContext(manager.fromName(step.resource), getContext());

		if (!cleaned) {
			LOGGER.log(Level.WARNING, "Cannot remove context from lockable resource waiting list. The context is not in the waiting list.");
		}

		getContext().onFailure(cause);
	}

	static void proceed(StepContext context, String resource, boolean inversePrecedence) {
		LockableResourcesStruct resourceHolder = new LockableResourcesStruct(Lists.newArrayList(resource));

		Run<?, ?> run;

		try {
			run = context.get(Run.class);

			context.get(TaskListener.class).getLogger().println("Lock acquired on [" + resource + "]");
		} catch (Exception e) {
			context.onFailure(e);

			return;
		}

		LOGGER.finest("Lock acquired on [" + resource + "] by " + run.getExternalizableId());

		context.newBodyInvoker().
						withCallback(new Callback(resourceHolder, inversePrecedence)).
						withDisplayName(null).
						start();
	}

	private static final class Callback extends BodyExecutionCallback.TailCall {

		private static final long serialVersionUID = 1L;

		private final LockableResourcesStruct resourceHolder;
		private final boolean inversePrecedence;

		Callback(LockableResourcesStruct resourceHolder, boolean inversePrecedence) {
			// It's granted to contain one item (and only one for now)
			this.resourceHolder = resourceHolder;
			this.inversePrecedence = inversePrecedence;
		}

		protected void finished(StepContext context) throws Exception {
			LockableResourcesManager manager = LockableResourcesManager.get();

			List<LockableResource> resources = manager.getResourcesWithNames(resourceHolder.resourceNames);

			manager.unlock(resources, context.get(Run.class), context, inversePrecedence);

			for (LockableResource resource : resources) {
				context.get(TaskListener.class).getLogger().println("Lock released on resource [" + resource + "]");

				LOGGER.finest("Lock released on [" + resource + "]");
			}
		}

	}

}
