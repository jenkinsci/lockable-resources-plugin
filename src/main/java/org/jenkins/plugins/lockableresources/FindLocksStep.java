package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class FindLocksStep extends Step implements Serializable {

  private static final long serialVersionUID = 148049840628540827L;

  @CheckForNull
  public String anyOfLabels = null;

  @CheckForNull
  public String noneOfLabels = null;

  @CheckForNull
  public String allOfLabels = null;

  @CheckForNull
  public String matching = null;


  @DataBoundSetter
  public void setAnyOfLabels(String anyOfLabels) {
    this.anyOfLabels = anyOfLabels;
  }

  @DataBoundSetter
  public void setNoneOfLabels(String noneOfLabels) {
    this.noneOfLabels = noneOfLabels;
  }

  @DataBoundSetter
  public void setAllOfLabels(String allOfLabels) {
    this.allOfLabels = allOfLabels;
  }

  @DataBoundSetter
  public void setMatching(String matching) {
    this.matching = matching;
  }

  @DataBoundConstructor
  public FindLocksStep() {
  }

  public boolean asPredicate(LockableResource lockableResource) {
    if (StringUtils.isNotBlank(anyOfLabels)) {
      List<String> anyLabelsList = Arrays.asList(anyOfLabels.split("\\s+"));
      List<String> resourceLabels = Arrays.asList(lockableResource.getLabels().split("\\s+"));
      if (anyLabelsList.stream().noneMatch(l -> resourceLabels.contains(l)))
        return false;
    }
    if (StringUtils.isNotBlank(noneOfLabels)) {
      List<String> noneOfLabelsList = Arrays.asList(noneOfLabels.split("\\s+"));
      List<String> resourceLabels = Arrays.asList(lockableResource.getLabels().split("\\s+"));
      if (noneOfLabelsList.stream().anyMatch(l -> resourceLabels.contains(l)))
        return false;
    }
    if (StringUtils.isNotBlank(allOfLabels)) {
      List<String> allOfLabelsList = Arrays.asList(allOfLabels.split("\\s+"));
      List<String> resourceLabels = Arrays.asList(lockableResource.getLabels().split("\\s+"));
      if (allOfLabelsList.stream().allMatch(l -> resourceLabels.contains(l)) == false)
        return false;
    }
    if (StringUtils.isNotBlank(matching)) {
      if (lockableResource.getName().matches(matching) == false) {
        return false;
      }
    }
    return true;
  }

  @Extension
  public static final class DescriptorImpl extends StepDescriptor {

    @Override
    public String getFunctionName() {
      return "findLocks";
    }

    @NonNull
    @Override
    public String getDisplayName() {
      return "Find existing shared resource";
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return false;
    }

    @Override
    public Set<Class<?>> getRequiredContext() {
      return Collections.singleton(TaskListener.class);
    }
  }

  @Override
  public String toString() {
    List<String> desc = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    if (StringUtils.isNotBlank(anyOfLabels)) {
      desc.add("anyLabels:" + anyOfLabels);
    }
    if (StringUtils.isNotBlank(allOfLabels)) {
      desc.add("allOfLabels:" + allOfLabels);
    }
    if (StringUtils.isNotBlank(noneOfLabels)) {
      desc.add("noneOfLabels:" + noneOfLabels);
    }
    if (StringUtils.isNotBlank(matching)) {
      desc.add("matching:" + matching);
    }
    if (desc.isEmpty()) {
      return "all";
    }
    else {
      return desc.stream().collect(Collectors.joining("; "));
    }
  }

  @Override
  public StepExecution start(StepContext context) throws Exception {
    return new FindLocksStepExecution(this, context);
  }
}
