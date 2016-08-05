package org.jenkins.plugins.lockableresources.actions;

import java.io.IOException;
import java.util.List;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.InvisibleAction;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;

@Restricted(NoExternalUse.class)
public class ResourceVariableNameAction extends InvisibleAction {

	private final StringParameterValue resourceNameParameter;

	public ResourceVariableNameAction(StringParameterValue resourceNameParameter) {
		this.resourceNameParameter = resourceNameParameter;
	}

	StringParameterValue getParameter() {
		return resourceNameParameter;
	}

	@Extension
	public static final class ResourceVariableNameActionEnvironmentContributor extends EnvironmentContributor {

		@Override
		public void buildEnvironmentFor(Run run, EnvVars envs, TaskListener listener)
						throws IOException, InterruptedException {
			List<ResourceVariableNameAction> actions = run.getActions(ResourceVariableNameAction.class);

			for (ResourceVariableNameAction action : actions) {
				if (action != null) {
					StringParameterValue parameter = action.getParameter();

					if (parameter != null && parameter.getValue() != null) {
						envs.put(parameter.getName(), String.valueOf(parameter.getValue()));
					}
				}
			}
		}

	}

}
