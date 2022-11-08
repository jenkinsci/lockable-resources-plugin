package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.io.Serializable;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;

public class LockableResourceProperty extends AbstractDescribableImpl<LockableResourceProperty>
  implements Serializable {

  private String name;
  private String value;

  @DataBoundConstructor
  public LockableResourceProperty() {
  }

  @DataBoundSetter
  public void setName(String name) {
    this.name = name;
  }

  @DataBoundSetter
  public void setValue(String value) {
    this.value = value;
  }

  @Exported
  public String getName() {
    return name;
  }

  @Exported
  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return name;
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<LockableResourceProperty> {

    @NonNull
    @Override
    public String getDisplayName() {
      return "Property";
    }
  }

  private static final long serialVersionUID = 1L;
}
