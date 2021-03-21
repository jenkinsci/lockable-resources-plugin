package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.util.FormValidation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class LockableResourceManagerTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @Test
    public void validationFailure() throws Exception {
        ParametersDefinitionProperty params = new ParametersDefinitionProperty(
              new StringParameterDefinition("param1", "resource1", "parameter 1"),
              new StringParameterDefinition("param2", "2", "parameter 2")
        );
        FreeStyleProject p = j.createFreeStyleProject("p");
        p.addProperty(params);

        RequiredResourcesProperty.DescriptorImpl d = new RequiredResourcesProperty.DescriptorImpl();
        LockableResourcesManager.get().createResource("resource1");
        LockableResource r = LockableResourcesManager.get().getResources().get(0);
        r.setLabels("some-label");

        assertEquals(
                "Only label, groovy expression, or resources can be defined, not more than one.",
                d.doCheckResourceNames("resource1", null, true, p).getMessage());
        assertEquals(
                "Only label, groovy expression, or resources can be defined, not more than one.",
                d.doCheckResourceNames("resource1", "some-label", false, p).getMessage());
        assertEquals(
             "Only label, groovy expression, or resources can be defined, not more than one.",
                d.doCheckResourceNames("resource1", "some-label", true, p).getMessage());
        assertEquals(
                "Only label, groovy expression, or resources can be defined, not more than one.",
                d.doCheckLabelName("some-label", "resource1", false, p).getMessage());
        assertEquals(
                "Only label, groovy expression, or resources can be defined, not more than one.",
                d.doCheckLabelName("some-label", null, true, p).getMessage());
        assertEquals(
                "Only label, groovy expression, or resources can be defined, not more than one.",
                d.doCheckLabelName("some-label", "resource1", true, p).getMessage());

        assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false, p));
        assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false, p));
        assertEquals(FormValidation.ok(), d.doCheckResourceNumber("1", "resource1", null,null, p));

        assertEquals(
                "The following resources do not exist: [resource3]",
                d.doCheckResourceNames("${param5} resource3", null,  false, p).getMessage());
        assertEquals(
                "The following parameters do not exist: [param5, param4]",
                d.doCheckResourceNames("${param5} ${param4} resource1", null,  false, p).getMessage());
        assertEquals(
                "The label does not exist: other-label",
                d.doCheckLabelName("other-label", null, false, p).getMessage());

        assertEquals(
                "The following resources cannot be validated as they contain parameter values: [${param1}]",
                d.doCheckResourceNames("${param1}", null,  false, p).getMessage());
        assertEquals(
                "The following resources cannot be validated as they contain parameter values: [xyz_${param1}]",
                d.doCheckResourceNames("xyz_${param1}", null,  false, p).getMessage());
        assertEquals(
                "The label cannot be validated as it contains a parameter value: ${param1}",
                d.doCheckLabelName("${param1}", null, false, p).getMessage());
        assertEquals(
                "The label cannot be validated as it contains a parameter value: ${param1}${param2}",
                d.doCheckLabelName("${param1}${param2}", null, false, p).getMessage());
        assertEquals(
                "The label cannot be validated as it contains a parameter value: resource${param2}",
                d.doCheckLabelName("resource${param2}", null, false, p).getMessage());
        assertEquals(
                "The value cannot be validated as it is a parameter value: ${param1}",
                d.doCheckResourceNumber("${param1}", null,null,  null, p).getMessage());

        assertEquals(
                "Could not parse the given value as integer.",
                d.doCheckResourceNumber("${param1}${param2}", null,null,  null, p).getMessage());
        assertEquals(
                "Could not parse the given value as integer.",
                d.doCheckResourceNumber("Five", null,null,  null, p).getMessage());

        assertEquals(
                "Given amount 4 is greater than amount of resources: 1.",
                d.doCheckResourceNumber("4", "resource1", null,null, p).getMessage());
        assertEquals(
                "Given amount 5 is greater than amount of resources: 1.",
                d.doCheckResourceNumber("5", null, "some-label",null, p).getMessage());
    }
}
