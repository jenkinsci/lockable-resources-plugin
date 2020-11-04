package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.common.collect.Iterables;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

public class LockableResourcesManagerTest {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void validationFailure() {
    RequiredResourcesProperty.DescriptorImpl d = new RequiredResourcesProperty.DescriptorImpl();
    LockableResourcesManager.get().createResource("resource1");
    LockableResource r = Iterables.getFirst(LockableResourcesManager.get().getReadOnlyResources(), null);
    assertNotNull(r);
    r.setLabels("some-label");

    assertEquals(
        "Only label, groovy expression, or resources can be defined, not more than one.",
        d.doCheckResourceNames("resource1", null, true).getMessage());
    assertEquals(
        "Only label, groovy expression, or resources can be defined, not more than one.",
        d.doCheckResourceNames("resource1", "some-label", false).getMessage());
    assertEquals(
        "Only label, groovy expression, or resources can be defined, not more than one.",
        d.doCheckResourceNames("resource1", "some-label", true).getMessage());
    assertEquals(
        "Only label, groovy expression, or resources can be defined, not more than one.",
        d.doCheckLabelName("some-label", "resource1", false).getMessage());
    assertEquals(
        "Only label, groovy expression, or resources can be defined, not more than one.",
        d.doCheckLabelName("some-label", null, true).getMessage());
    assertEquals(
        "Only label, groovy expression, or resources can be defined, not more than one.",
        d.doCheckLabelName("some-label", "resource1", true).getMessage());

    assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false));
    assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false));
  }

  @Test
  public void checkConcurrentAccess() throws ExecutionException {
    // this dummy resource creates another resource when called
    final LockableResource dummyResource = new LockableResource("mock") {
      @Override
      public String getQueueItemProject() {
        // used by LockableResourcesManager.checkCurrentResourcesStatus
        // used by LockableResourcesManager.getResourcesFromProject
        LockableResourcesManager.get().resources.add(new LockableResource("check-getQueueItemProject"));
        return "project";
      }

      @Override
      public String getLabels() {
        // used by LockableResourcesManager.getAllLabels
        LockableResourcesManager.get().resources.add(new LockableResource("check-getLabels"));
        return super.getLabels();
      }

      @Override
      public String getName() {
        // used by LockableResourcesManager.fromName
        LockableResourcesManager.get().resources.add(new LockableResource("check-getName"));
        return super.getName();
      }

      @Override
      public boolean isEphemeral() {
        // used by LockableResourcesManager.getDeclaredResources
        LockableResourcesManager.get().resources.add(new LockableResource("check-isEphemeral"));
        return super.isEphemeral();
      }

      @Override
      public boolean isLocked() {
        // used by LockableResourcesManager.getResourcesFromBuild
        LockableResourcesManager.get().resources.add(new LockableResource("check-isLocked"));
        return super.isLocked();
      }

      @Override
      public boolean isValidLabel(String candidate, Map<String, Object> params) {
        // used by LockableResourcesManager.getResourcesWithLabel
        LockableResourcesManager.get().resources.add(new LockableResource("check-isValidLabel"));
        return super.isValidLabel(candidate, params);
      }

      @Override
      public boolean scriptMatches(@Nonnull SecureGroovyScript script, Map<String, Object> params) {
        // used by LockableResourcesManager.getResourcesMatchingScript
        LockableResourcesManager.get().resources.add(new LockableResource("check-scriptMatches"));
        return false;
      }
    };
    LockableResourcesManager.get().resources.add(dummyResource);

    // those methods mostly iterates over resources and interacts with each one
    LockableResourcesManager.get().checkCurrentResourcesStatus(Collections.emptyList(), null, 0, null);
    LockableResourcesManager.get().fromName("");
    LockableResourcesManager.get().getAllLabels();
    LockableResourcesManager.get().getDeclaredResources();
    LockableResourcesManager.get().getFreeResourceAmount("");
    LockableResourcesManager.get().getResourcesFromBuild(null);
    LockableResourcesManager.get().getResourcesFromProject("");
    LockableResourcesManager.get().getResourcesWithLabel("", null);

    LockableResourcesManager.get().resources.removeIf(((Predicate<LockableResource>) dummyResource::equals).negate()); // avoid side effects
    SecureGroovyScript fakeScript = new SecureGroovyScript("", false, Collections.emptyList());
    LockableResourcesManager.get().getResourcesMatchingScript(fakeScript, Collections.emptyMap());
  }
}
