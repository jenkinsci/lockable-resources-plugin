package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.AccessDeniedException3;
import hudson.util.FormValidation;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.springframework.security.core.context.SecurityContextHolder;

public class LockableResourceManagerTest {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void validationFailure() throws Exception {
    RequiredResourcesProperty.DescriptorImpl d = new RequiredResourcesProperty.DescriptorImpl();
    LockableResourcesManager.get().createResource("resource1");
    LockableResource r = LockableResourcesManager.get().getResources().get(0);
    r.setLabels("some-label");

    assertEquals(
      "Only resource label, groovy expression, or resource names can be defined, not more than one.",
      d.doCheckResourceNames("resource1", null, true, null).getMessage());
    assertEquals(
      "Only resource label, groovy expression, or resource names can be defined, not more than one.",
      d.doCheckResourceNames("resource1", "some-label", false, null).getMessage());
    assertEquals(
      "Only resource label, groovy expression, or resource names can be defined, not more than one.",
      d.doCheckResourceNames("resource1", "some-label", true, null).getMessage());
    assertEquals(
      "Only resource label, groovy expression, or resource names can be defined, not more than one.",
      d.doCheckLabelName("some-label", "resource1", false, null).getMessage());
    assertEquals(
      "Only resource label, groovy expression, or resource names can be defined, not more than one.",
      d.doCheckLabelName("some-label", null, true, null).getMessage());
    assertEquals(
      "Only resource label, groovy expression, or resource names can be defined, not more than one.",
      d.doCheckLabelName("some-label", "resource1", true, null).getMessage());

    assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false, null));
    assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false, null));

    
    j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
    j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
      .grant(Jenkins.READ).everywhere().to("user")
      .grant(Jenkins.READ, Item.CONFIGURE).everywhere().to("cfg_user")
      .grant(Jenkins.ADMINISTER).everywhere().to("manager")
    );

    j.buildAndAssertSuccess(j.createFreeStyleProject("aProject"));
    Item item = j.jenkins.getItem("aProject");
    assertNotNull(item);

    User user = User.get("user", true, Collections.emptyMap());
    SecurityContextHolder.getContext().setAuthentication(user.impersonate2());
    // the 'user' has no permission
    assertThrows(AccessDeniedException3.class, () -> d.doCheckResourceNames("resource1", null, false, item));
    assertThrows(AccessDeniedException3.class, () -> d.doCheckLabelName("some-label", null, false, item));
    
    User cfg_user = User.get("cfg_user", true, Collections.emptyMap());
    SecurityContextHolder.getContext().setAuthentication(cfg_user.impersonate2());
    assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false, item));
    assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false, item));

    
    User manager = User.get("manager", true, Collections.emptyMap());
    SecurityContextHolder.getContext().setAuthentication(manager.impersonate2());
    assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false, item));
    assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false, item));
  }
}
