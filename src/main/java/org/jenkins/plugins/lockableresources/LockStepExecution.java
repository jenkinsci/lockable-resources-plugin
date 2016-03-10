package org.jenkins.plugins.lockableresources;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

	@Override
	public boolean start() throws Exception {
		tryLock(0);
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
		if (LockableResourcesManager.get().lock(resourceHolder.required, run)) {
			listener.getLogger().println("Lock aquired on [" + step.resource + "]");
			body = getContext().newBodyInvoker().
				withCallback(new Callback(resourceHolder, run)).
				withDisplayName(null).
				start();
			return true;
		} else {
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

	private static final class Callback extends BodyExecutionCallback.TailCall {

		private final LockableResourcesStruct resourceHolder;
		private final Run<?, ?> run;

		Callback(LockableResourcesStruct resourceHolder, Run<?, ?> run) {
			this.resourceHolder = resourceHolder;
			this.run = run;
		}

		@Override
		protected void finished(StepContext context) throws Exception {
			LockableResourcesManager.get().unlock(resourceHolder.required, run);
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
