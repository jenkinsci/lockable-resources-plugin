package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepFormPermissionTest {

    private JenkinsRule j;
    private WorkflowJob job;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        job = j.createProject(WorkflowJob.class, "p");

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader")
                .grant(Item.READ)
                .onItems(job)
                .to("reader")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));
    }

    @Test
    @Issue("633")
    void userWithoutConfigureGetsNoPermissionErrors() {
        LockStep.DescriptorImpl d = j.jenkins.getDescriptorByType(LockStep.DescriptorImpl.class);
        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertEquals(FormValidation.Kind.OK, d.doCheckLabel("label1", null, job).kind);
            assertEquals(FormValidation.Kind.OK, d.doCheckResource("resource1", null, job).kind);
            assertEquals(FormValidation.Kind.OK, d.doCheckResourceSelectStrategy("sequential", job).kind);
            assertEquals(strategyNames(), names(d.doFillResourceSelectStrategyItems(job)));
            assertEquals(List.of("SECONDS", "MINUTES", "HOURS"), values(d.doFillTimeoutUnitItems(job)));
            assertTrue(d.doAutoCompleteResource("res", job).getValues().isEmpty());
        }
    }

    @Test
    @Issue("633")
    void adminBehaviourUnchanged() {
        LockStep.DescriptorImpl d = j.jenkins.getDescriptorByType(LockStep.DescriptorImpl.class);
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertEquals(FormValidation.Kind.OK, d.doCheckLabel("label1", null, job).kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckLabel("unknown-label", null, job).kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckLabel(null, null, null).kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckResourceSelectStrategy("bogus", job).kind);
            assertEquals(strategyNames(), names(d.doFillResourceSelectStrategyItems(job)));
            assertEquals(List.of("SECONDS", "MINUTES", "HOURS"), values(d.doFillTimeoutUnitItems(job)));
            assertEquals(
                    List.of("resource1"), d.doAutoCompleteResource("res", job).getValues());
        }
    }

    private static List<String> strategyNames() {
        return Arrays.stream(ResourceSelectStrategy.values()).map(Enum::name).toList();
    }

    private static List<String> names(ListBoxModel model) {
        return model.stream().map(o -> o.name).toList();
    }

    private static List<String> values(ListBoxModel model) {
        return model.stream().map(o -> o.value).toList();
    }
}
