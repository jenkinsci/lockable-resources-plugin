package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
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

	private volatile BodyExecution body;
	private static final Logger LOGGER = Logger.getLogger(LockStepExecution.class.getName());

	@Override
	public boolean start() throws Exception {
		listener.getLogger().println("Trying to acquire lock on [" + step.resource + "]");
		LockableResourcesStruct resourceHolder = new LockableResourcesStruct(step.resource);
		if(LockableResourcesManager.get().lock(resourceHolder.required, run, getContext())) {
			proceed(getContext(), step.resource);
		} else {
			// we have to wait
			listener.getLogger().println("Resource locked, waiting...");
		}
		return false;
	}

	public static void proceed(StepContext context, String resource) {
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
			withCallback(new Callback(resourceHolder, r)).
			withDisplayName(null).
			start();
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
			LockableResourcesManager.get().unlock(resourceHolder.required, run, context);
			context.get(TaskListener.class).getLogger().println("Lock released on resouce [" + resourceHolder.required.get(0) + "]");
			LOGGER.finest("Lock released on [" + resourceHolder.required.get(0) + "]");
			context.onSuccess(null);
		}

		private static final long serialVersionUID = 1L;

	}

	@Override
	public void stop(Throwable cause) throws Exception {
		// NO-OP
	}

}
