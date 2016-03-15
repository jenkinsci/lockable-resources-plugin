package org.jenkins.plugins.lockableresources;

import java.io.IOException;
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
	private LockStep step;

	@StepContextParameter
	private transient Run<?, ?> run;

	@StepContextParameter
	private transient TaskListener listener;

	private transient volatile ScheduledFuture<?> task;
	private volatile BodyExecution body;
	private final String id = UUID.randomUUID().toString();
	private static final Logger LOGGER = Logger.getLogger(LockStepExecution.class.getName());

	@Override
	public boolean start() throws Exception {
		listener.getLogger().println("Trying to acquire lock on [" + step.resource + "]");
		if (!lockAndProceed()) {
			listener.getLogger().println(LockableResourcesManager.get().getLockCause(step.resource));
			listener.getLogger().println("Waiting for lock...");
			tryLock(0);
		}
		return false;
	}

	@Override
	public void onResume() {
		super.onResume();
		//LockableResourcesManager.get().load();
		if (body == null) {
			// Restarted while waiting to lock the resource
			LOGGER.fine("Resuming lock step on [" + run.getExternalizableId() + "], retrying to acquire lock");
			tryLock(0);
		}
		// the body was already started, nothing to do here
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

	private synchronized boolean lockAndProceed() {
		LockableResourcesStruct resourceHolder = new LockableResourcesStruct(step.resource);
		LOGGER.finest("Trying to acquire [" + step.resource + "] by " + run.getExternalizableId());
		// force load from disk to avoid not commited memory between threads
		LockableResourcesManager.get().addToQueue(resourceHolder.required, run);
		if (LockableResourcesManager.get().isNextInQueue(resourceHolder.required, run) && 
				LockableResourcesManager.get().lock(resourceHolder.required, run)) {
			listener.getLogger().println("Lock acquired on [" + step.resource + "]");
			LOGGER.finest("Lock acquired on [" + step.resource + "] by " + run.getExternalizableId());
			body = getContext().newBodyInvoker().
				withCallback(new Callback(resourceHolder, run)).
				withDisplayName(null).
				start();
			return true;
		} else {
			//listener.getLogger().println(LockableResourcesManager.get().getLockCause(step.resource));
			return false;
		}
	}

	private static void retry(final String id, final long delay) {
		// retry only if the the execution of this step has not being somehow stopped
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

	private static final class Callback extends BodyExecutionCallback.TailCall {

		private final LockableResourcesStruct resourceHolder;
		private transient Run<?, ?> run;
		private final String buildExternalizableId;

		Callback(LockableResourcesStruct resourceHolder, Run<?, ?> run) {
			// It's granted to contain one item (and only one for now)
			this.resourceHolder = resourceHolder;
			this.run = run;
			this.buildExternalizableId = run.getExternalizableId();
		}

		protected void finished(StepContext context) throws Exception {
			unlock(context);
		}

		private void unlock(StepContext context) throws IOException, InterruptedException {
			if (run == null && buildExternalizableId != null) {
				run = Run.fromExternalizableId(buildExternalizableId);
			}
			LockableResourcesManager.get().unlock(resourceHolder.required, run);
			LockableResourcesManager.get().save();
			// It's granted to contain one (and only one for now)
			context.get(TaskListener.class).getLogger().println("Lock released on resouce [" + resourceHolder.required.get(0) + "]");
			LOGGER.finest("Lock released on [" + resourceHolder.required.get(0) + "]");
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
