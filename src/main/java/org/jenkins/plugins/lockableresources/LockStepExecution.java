package org.jenkins.plugins.lockableresources;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;

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

	@Override
	public boolean start() throws Exception {
		tryLock(0);
		return false;
	}

	private void tryLock(long delay) {
		Timer.get().schedule(new Runnable() {
			@Override
			public void run() {
				if (!proceed()) {
					tryLock(1000); // try to lock every second
				}
			}
		}, delay, TimeUnit.MILLISECONDS);
	}

	private boolean proceed() {
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
	}

	private static final long serialVersionUID = 1L;

}
