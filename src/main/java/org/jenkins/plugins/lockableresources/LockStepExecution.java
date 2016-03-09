package org.jenkins.plugins.lockableresources;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import com.google.common.base.Function;
import com.google.inject.Inject;

import hudson.Util;
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
		tryLock();
		return false;
	}

	private void tryLock() {
		getContext().saveState();
		Timer.get().schedule(new Runnable() {
			@Override
			public void run() {
				LockableResource resource = new LockableResource(step.resource);
				if (!proceed()) {
					tryLock();
				}
			}
		}, 1000, TimeUnit.MILLISECONDS);
	}
	
	private boolean proceed() {
		LockableResource resource = new LockableResource(step.resource);
		if (LockableResourcesManager.get().lock(Arrays.asList(new LockableResource[] { resource }), run)) {
			listener.getLogger().println("Lock aquired on [" + step.resource + "]");
			body = getContext().newBodyInvoker().
				withCallback(new Callback(resource, run)).
				withDisplayName(null).
				start();
			return true;
		} else {
			return false;
		}
	}

	private static final class Callback extends BodyExecutionCallback.TailCall {

		private final LockableResource resource;
		private final Run<?, ?> run;

		Callback(LockableResource resource, Run<?, ?> run) {
			this.resource = resource;
			this.run = run;
		}

		@Override
		protected void finished(StepContext context) throws Exception {
			LockableResourcesManager.get().unlock(Arrays.asList(new LockableResource[] { resource }), run);
		}

	}

	@Override
	public void stop(Throwable cause) throws Exception {
		// TODO does it need implementation?
	}

	private static final long serialVersionUID = 1L;

}
