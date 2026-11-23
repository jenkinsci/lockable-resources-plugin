package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Descriptor;
import hudson.model.Item;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@Issue("https://github.com/jenkinsci/lockable-resources-plugin/issues/896")
class LockStepLabelAutoCompleteTest {

    private JenkinsRule j;
    private WorkflowJob job;
    private WorkflowJob otherJob;
    private List<Descriptor<?>> descriptors;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.createResourceWithLabel("phone", "android-device android-testing");
        manager.createResourceWithLabel("emulator", "android-emulator android-testing");
        manager.createResourceWithLabel("server", "linux-server windows-agent Android-device");
        job = j.createProject(WorkflowJob.class, "p");
        otherJob = j.createProject(WorkflowJob.class, "other");
        descriptors = List.of(
                j.jenkins.getDescriptorByType(LockStep.DescriptorImpl.class),
                j.jenkins.getDescriptorByType(LockStepResource.DescriptorImpl.class));

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .toAuthenticated()
                .grant(Item.CONFIGURE)
                .onItems(job)
                .to("configurer")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));
    }

    @Test
    void suggestsLabelsMatchingTheTypedPrefix() throws Exception {
        try (JenkinsRule.WebClient client = j.createWebClient().withBasicCredentials("admin")) {
            for (Descriptor<?> descriptor : descriptors) {
                assertThat(
                        suggestions(client, descriptor, job.getUrl(), "android"),
                        containsInAnyOrder("android-device", "android-emulator", "android-testing"));
                assertEquals(List.of("android-device"), suggestions(client, descriptor, job.getUrl(), " android-d "));
                assertEquals(List.of("linux-server"), suggestions(client, descriptor, job.getUrl(), "lin"));
                assertEquals(List.of("windows-agent"), suggestions(client, descriptor, job.getUrl(), "win"));
                assertEquals(List.of("Android-device"), suggestions(client, descriptor, job.getUrl(), "Android"));
            }
        }
    }

    @Test
    void emptyOrUnknownPrefixesHaveNoSuggestions() throws Exception {
        try (JenkinsRule.WebClient client = j.createWebClient().withBasicCredentials("admin")) {
            for (Descriptor<?> descriptor : descriptors) {
                assertTrue(suggestions(client, descriptor, job.getUrl(), null).isEmpty());
                for (String value : List.of("", "   ", "unknown", "ANDROID")) {
                    assertTrue(
                            suggestions(client, descriptor, job.getUrl(), value).isEmpty());
                }
            }
        }
    }

    @Test
    void configurePermissionIsScopedToTheJob() throws Exception {
        try (JenkinsRule.WebClient client = j.createWebClient().withBasicCredentials("configurer")) {
            for (Descriptor<?> descriptor : descriptors) {
                assertEquals(List.of("linux-server"), suggestions(client, descriptor, job.getUrl(), "lin"));
                assertTrue(suggestions(client, descriptor, otherJob.getUrl(), "lin")
                        .isEmpty());
                assertTrue(suggestions(client, descriptor, "", "lin").isEmpty());
            }
        }
    }

    @Test
    void readersCannotDiscoverLabelsAndAdminsCanUseTheGlobalEndpoint() throws Exception {
        try (JenkinsRule.WebClient reader = j.createWebClient().withBasicCredentials("reader");
                JenkinsRule.WebClient admin = j.createWebClient().withBasicCredentials("admin")) {
            for (Descriptor<?> descriptor : descriptors) {
                assertTrue(suggestions(reader, descriptor, job.getUrl(), "lin").isEmpty());
                assertTrue(suggestions(reader, descriptor, "", "lin").isEmpty());
                assertEquals(List.of("linux-server"), suggestions(admin, descriptor, "", "lin"));
            }
        }
    }

    @Test
    void labelFieldsDiscoverAutocomplete() throws Exception {
        try (JenkinsRule.WebClient client = j.createWebClient().withBasicCredentials("admin")) {
            client.executeOnServer(() -> {
                for (Descriptor<?> descriptor : descriptors) {
                    Map<String, Object> attributes = new HashMap<>();
                    // This is how f:textbox discovers the endpoint for field="label".
                    descriptor.calcAutoCompleteSettings("label", attributes);
                    Object url = attributes.get("autoCompleteUrl");
                    assertNotNull(url, descriptor.getId());
                    assertTrue(url.toString().endsWith(descriptor.getDescriptorUrl() + "/autoCompleteLabel"));
                }
                return null;
            });
        }
    }

    @Test
    void autocompleteRequiresPost() throws Exception {
        try (JenkinsRule.WebClient client = j.createWebClient().withBasicCredentials("admin")) {
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            for (Descriptor<?> descriptor : descriptors) {
                WebRequest request = request(descriptor, job.getUrl(), HttpMethod.GET);
                assertEquals(405, client.getPage(request).getWebResponse().getStatusCode());
            }
        }
    }

    private List<String> suggestions(
            JenkinsRule.WebClient client, Descriptor<?> descriptor, String context, String value) throws Exception {
        WebRequest request = request(descriptor, context, HttpMethod.POST);
        if (value != null) {
            request.setRequestParameters(List.of(new NameValuePair("value", value)));
        }
        client.addCrumb(request);
        var response = client.getPage(request).getWebResponse();
        assertEquals(200, response.getStatusCode());
        JSONObject json = JSONObject.fromObject(response.getContentAsString());
        List<String> names = new ArrayList<>();
        for (Object suggestion : json.getJSONArray("suggestions")) {
            names.add(((JSONObject) suggestion).getString("name"));
        }
        return names;
    }

    private WebRequest request(Descriptor<?> descriptor, String context, HttpMethod method) throws Exception {
        return new WebRequest(
                new URL(j.getURL(), context + descriptor.getDescriptorUrl() + "/autoCompleteLabel"), method);
    }
}
