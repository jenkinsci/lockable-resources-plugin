package org.jenkins.plugins.lockableresources;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;

@ExportedBean
public class LockableResourceProperty extends AbstractDescribableImpl<LockableResourceProperty> {
	private String name;
	private String value;
	
	@DataBoundConstructor
	public LockableResourceProperty(String name, String value) {
		super();
		this.name = name;
		this.value = value;
	}
	
	@Exported
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	@Exported
	public String getValue() {
		return value;
	}
	public void setValue(String value) {
		this.value = value;
	}

	@Extension
    public static class DescriptorImpl extends Descriptor<LockableResourceProperty> {
        @Override
        public String getDisplayName() {
        	return "";
        }
    } 
}
