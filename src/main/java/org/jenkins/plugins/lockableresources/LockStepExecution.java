package org.jenkins.plugins.lockableresources;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import com.google.common.base.Function;
import com.google.inject.Inject;

import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.util.Timer;

public class LockStepExecution extends AbstractStepExecutionImpl {

	@Inject(optional = true)
	private transient LockStep step;

	@StepContextParameter
	private transient Run<?, ?> run;

	@StepContextParameter
	private transient TaskListener listener;

	private transient volatile ScheduledFuture<?> task;
	private BodyExecution body;
	private final String id = UUID.randomUUID().toString();
	private static final Logger LOGGER = Logger.getLogger(LockStepExecution.class.getName());

	@Override
	public boolean start() throws Exception {
		listener.getLogger().println("Trying to acquire lock on [" + step.resource + "]");
		if (!lockAndProceed()) {
			listener.getLogger().println(new LockableResourcesStruct(step.resource).required.get(0).getLockCause());
			listener.getLogger().println("Waiting for lock...");
			tryLock(0);
		}
		return false;
	}

	private void tryLock(long delay) {
		task = Timer.get().schedule(new Runnable() {
			@Override
			public void run() {
				task = null;
				if (!lockAndProceed()) {
					retry(id, 1000); // try to lock every second
				}
			}
		}, delay, TimeUnit.MILLISECONDS);
	}

	private boolean lockAndProceed() {
		LockableResourcesStruct resourceHolder = new LockableResourcesStruct(step.resource);
		LOGGER.finest("Trying to acquire [" + step.resource + "]");
		if (LockableResourcesManager.get().lock(resourceHolder.required, run)) {
			listener.getLogger().println("Lock acquired on [" + step.resource + "]");
			LOGGER.finest("Lock acquired on [" + step.resource + "]");
			body = getContext().newBodyInvoker().
				withCallback(new Callback(resourceHolder, run)).
				withDisplayName(null).
				start();
			return true;
		} else {
			resourceHolder.required.get(0).getLockCause();
			return false;
		}
	}

	private static void retry(final String id, final long delay) {
		// retry only if the the execution of this step has not being requested to stop
		StepExecution.applyAll(LockStepExecution.class, new Function<LockStepExecution, Void>() {
			@Override
			public Void apply(@Nonnull LockStepExecution execution) {
				if (execution.id.equals(id)) {
					execution.tryLock(delay);
				}
				return null;
			}
		});
	}

	private static final class Callback extends BodyExecutionCallback {

		private final LockableResourcesStruct resourceHolder;
		private transient Run<?, ?> run;
		private final String buildExternalizableId;

		Callback(LockableResourcesStruct resourceHolder, Run<?, ?> run) {
			// It's granted to contain one item (and only one for now)
			this.resourceHolder = resourceHolder;
			this.run = run;
			this.buildExternalizableId = run.getExternalizableId();
		}

		@Override
		public void onSuccess(StepContext context, Object result) {
			unlock(context);
			context.onSuccess(result);
		}

		@Override
		public void onFailure(StepContext context, Throwable t) {
			unlock(context);
			context.onFailure(t);
		}

		private void unlock(StepContext context) {
			if (run == null && buildExternalizableId != null) {
				run = Run.fromExternalizableId(buildExternalizableId);
			}
			LockableResourcesManager.get().unlock(resourceHolder.required, run);
			try {
				// It's granted to contain one (and only one for now)
				context.get(TaskListener.class).getLogger().println("Lock released on resouce [" + resourceHolder.required.get(0) + "]");
				LOGGER.finest("Lock released on [" + resourceHolder.required.get(0) + "]");
			} catch (Exception e) {
				context.onFailure(e);
			}
		}

		private static final long serialVersionUID = 1L;

	}

	@Override
	public void stop(Throwable cause) throws Exception {
		if (body != null) {
			body.cancel(cause);
		}
		if (task != null) {
			task.cancel(false);
		}
		getContext().onFailure(cause);
	}

	private static final long serialVersionUID = 1L;

}
