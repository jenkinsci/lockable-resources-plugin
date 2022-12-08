package org.jenkins.plugins.lockableresources.actions;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.InvisibleAction;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Restricted(NoExternalUse.class)
public class ResourceVariableNameAction extends InvisibleAction {

  private final List<StringParameterValue> resourceNameParameter;

  public ResourceVariableNameAction(List<StringParameterValue> r) {
    this.resourceNameParameter = r;
  }

  List<StringParameterValue> getParameter() {
    return resourceNameParameter;
  }

  @Extension
  public static final class ResourceVariableNameActionEnvironmentContributor extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(@NonNull Run r, @NonNull EnvVars envs, @NonNull TaskListener listener) {
      ResourceVariableNameAction a = r.getAction(ResourceVariableNameAction.class);
      if (a != null && a.getParameter() != null) {
        for (StringParameterValue envToSet : a.getParameter()) {
          envs.override(envToSet.getName(), envToSet.getValue());
        }
      }
    }

  }
}
