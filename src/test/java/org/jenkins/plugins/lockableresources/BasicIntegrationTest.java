package org.jenkins.plugins.lockableresources;

import com.google.common.collect.Lists;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.IOException;
import java.util.Collections;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockBuilder;
import org.jvnet.hudson.test.TestExtension;

public class BasicIntegrationTest {
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    @Issue("JENKINS-34853")
    public void security170fix() throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        FreeStyleProject project = jenkinsRule.createFreeStyleProject("project");

        project.addProperty(new RequiredResourcesProperty(Collections.singletonList(new RequiredResources("resource1", null, 0)), "resourceNameVar"));
        project.getBuildersList().add(new PrinterBuilder("resourceNameVar"));

        FreeStyleBuild build = project.scheduleBuild2(0).get();

        jenkinsRule.assertLogContains("resourceNameVar: resource1", build);
        jenkinsRule.assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    public void valid_resources_by_names_in_property_variable() throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        LockableResourcesManager.get().createResource("resource2");
        LockableResourcesManager.get().createResource("resource3");
        LockableResourcesManager.get().createResource("resource4");

        FreeStyleProject project = jenkinsRule.createFreeStyleProject("project");

        project.addProperty(
                new RequiredResourcesProperty(Lists.newArrayList(
                        new RequiredResources("resource1", null, 0),
                        new RequiredResources("resource2 resource4", null, 0)),
                        "resourceNameVar"));
        project.getBuildersList().add(new PrinterBuilder("resourceNameVar"));

        FreeStyleBuild build = project.scheduleBuild2(0).get();

        jenkinsRule.assertLogContains("resourceNameVar: resource1, resource2, resource4", build);
        jenkinsRule.assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    public void valid_resources_by_capabilities_in_property_variable() throws Exception {
        LockableResourcesManager.get().createResource("resource1", "capa1 capa2");
        LockableResourcesManager.get().createResource("resource2", "capa1 capa3");
        LockableResourcesManager.get().createResource("resource3", "capa0 capa2 capa4");
        LockableResourcesManager.get().createResource("resource4", "capa3 capa4 capa5");
        LockableResourcesManager.get().createResource("resource5", "capa4 capa5");

        FreeStyleProject project1 = jenkinsRule.createFreeStyleProject("project1");
        project1.addProperty(
                new RequiredResourcesProperty(Lists.newArrayList(
                        new RequiredResources(null, "capa1", 2), // resource1 + resource2
                        new RequiredResources(null, "capa2", 1), // resource3
                        new RequiredResources(null, "capa3", 0)), // resource4
                        "resourceNameVar"));
        project1.getBuildersList().add(new PrinterBuilder("resourceNameVar"));
        FreeStyleBuild build1 = project1.scheduleBuild2(0).get();

        jenkinsRule.assertLogContains("resourceNameVar: resource1, resource2, resource3, resource4", build1);
        jenkinsRule.assertBuildStatus(Result.SUCCESS, build1);

        FreeStyleProject project2 = jenkinsRule.createFreeStyleProject("project2");
        project2.addProperty(
                new RequiredResourcesProperty(Lists.newArrayList(
                        new RequiredResources(null, "capa2", 0), // resource1 + resource3
                        new RequiredResources(null, "capa4 capa5", 2)), // resource4 + resource5
                        "otherVarName"));
        project2.getBuildersList().add(new PrinterBuilder("otherVarName"));
        FreeStyleBuild build2 = project2.scheduleBuild2(0).get();

        jenkinsRule.assertLogContains("otherVarName: resource1, resource3, resource4, resource5", build2);
        jenkinsRule.assertBuildStatus(Result.SUCCESS, build2);
    }

    @Test
    public void valid_resources_mixed_in_property_variable() throws Exception {
        LockableResourcesManager.get().createResource("resource1", "capa1 capa2");
        LockableResourcesManager.get().createResource("resource2", "capa1 capa3");
        LockableResourcesManager.get().createResource("resource3", "capa1 capa4");
        LockableResourcesManager.get().createResource("resource4", "capa0 capa2 capa5");
        LockableResourcesManager.get().createResource("resource5", "capa2 capa5");
        LockableResourcesManager.get().createResource("resource6", "capa0");

        FreeStyleProject project = jenkinsRule.createFreeStyleProject("project");
        project.addProperty(
                new RequiredResourcesProperty(Lists.newArrayList(
                        new RequiredResources("resource1", "capa1", 1), // resource1 + resource3
                        new RequiredResources(null, "capa3", 1), // resource2
                        new RequiredResources("resource6", "capa2 capa5", 0)), // resource5 + resource6
                        "resourceNameVar"));
        project.getBuildersList().add(new PrinterBuilder("resourceNameVar"));
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        jenkinsRule.assertLogContains("resourceNameVar: resource1, resource2, resource3, resource4, resource5, resource6", build);
        jenkinsRule.assertBuildStatus(Result.SUCCESS, build);
    }

    @TestExtension
    public static class PrinterBuilder extends MockBuilder {
        private final String varName;

        public PrinterBuilder() {
            super(Result.SUCCESS);
            varName = "---";
        }

        public PrinterBuilder(String varName) {
            super(Result.SUCCESS);
            this.varName = varName;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            EnvVars env = build.getEnvironment(listener);
            listener.getLogger().println(varName + ": " + env.get(varName));

            return true;
        }
    }
}
