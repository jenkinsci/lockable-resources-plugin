package org.jenkins.plugins.lockableresources.actions;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.InvisibleAction;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import java.io.IOException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Restricted(NoExternalUse.class)
public class ResourceVariableNameAction extends InvisibleAction {

	private final StringParameterValue resourceNameParameter;

	public ResourceVariableNameAction(StringParameterValue r) {
		this.resourceNameParameter = r;
	}

	StringParameterValue getParameter() {
		return resourceNameParameter;
	}

	@Extension
	public static final class ResourceVariableNameActionEnvironmentContributor extends EnvironmentContributor {

		@Override
		public void buildEnvironmentFor(Run r, EnvVars envs, TaskListener listener)
				throws IOException, InterruptedException {
			ResourceVariableNameAction a = r.getAction(ResourceVariableNameAction.class);
			if (a != null && a.getParameter() != null && a.getParameter().getValue() != null) {
				envs.put(a.getParameter().getName(), String.valueOf(a.getParameter().getValue()));
			}
		}

	}

}
