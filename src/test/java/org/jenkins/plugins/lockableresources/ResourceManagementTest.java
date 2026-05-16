/* SPDX-License-Identifier: MIT */
package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ResourceManagementTest {

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    private JenkinsRule.WebClient loginAsAdmin(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
        j.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("admin");
        wc.getOptions().setThrowExceptionOnScriptError(false);
        return wc;
    }

    @Test
    void createResourceViaEndpoint(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = new URL(j.getURL(), "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, org.htmlunit.HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", "test-resource-1"));
        params.add(new NameValuePair("description", "A test resource"));
        params.add(new NameValuePair("labels", "label1 label2"));
        req.setRequestParameters(params);

        wc.getPage(req);

        LockableResource resource = LockableResourcesManager.get().fromName("test-resource-1");
        assertThat("Resource should be created", resource, is(not(nullValue())));
        assertThat(resource.getDescription(), is("A test resource"));
        assertThat(resource.getLabels(), is("label1 label2"));
    }

    @Test
    void createResourceWithNameOnly(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = new URL(j.getURL(), "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, org.htmlunit.HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", "minimal-resource"));
        req.setRequestParameters(params);

        wc.getPage(req);

        LockableResource resource = LockableResourcesManager.get().fromName("minimal-resource");
        assertThat("Resource should be created", resource, is(not(nullValue())));
    }

    @Test
    void createDuplicateResourceReturns409(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("existing-resource", null);

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(j.getURL(), "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, org.htmlunit.HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", "existing-resource"));
        req.setRequestParameters(params);

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(409));
    }

    @Test
    void createResourceWithEmptyNameReturns400(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(j.getURL(), "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, org.htmlunit.HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", ""));
        req.setRequestParameters(params);

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(400));
    }

    @Test
    void deleteResourceViaEndpoint(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("to-delete", null);
        assertThat(LockableResourcesManager.get().fromName("to-delete"), is(not(nullValue())));

        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = new URL(j.getURL(), "lockable-resources/deleteResource");
        WebRequest req = new WebRequest(url, org.htmlunit.HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("resource", "to-delete"));
        req.setRequestParameters(params);

        wc.getPage(req);

        assertThat("Resource should be deleted", LockableResourcesManager.get().fromName("to-delete"), is(nullValue()));
    }

    @Test
    void deleteNonExistentResourceReturns404(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(j.getURL(), "lockable-resources/deleteResource");
        WebRequest req = new WebRequest(url, org.htmlunit.HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("resource", "no-such-resource"));
        req.setRequestParameters(params);

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(404));
    }

    @Test
    void deleteReservedResourceReturns423(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.createResourceWithLabel("reserved-resource", null);
        LockableResource resource = manager.fromName("reserved-resource");
        assertThat(resource, is(not(nullValue())));
        List<LockableResource> resources = new ArrayList<>();
        resources.add(resource);
        manager.reserve(resources, "testUser");

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(j.getURL(), "lockable-resources/deleteResource");
        WebRequest req = new WebRequest(url, org.htmlunit.HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("resource", "reserved-resource"));
        req.setRequestParameters(params);

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(423));
    }

    @Test
    void addResourceButtonVisibleOnPage(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        HtmlPage page = wc.goTo("lockable-resources");
        String content = page.getWebResponse().getContentAsString();
        assertThat(content, containsString("lr-add-resource-btn"));
    }

    @Test
    void deleteButtonVisibleForFreeResource(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("free-resource", null);

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        HtmlPage page = wc.goTo("lockable-resources");
        String content = page.getWebResponse().getContentAsString();
        assertThat(content, containsString("lockable-resources-delete-button"));
    }

    // --- JSON body tests (matches how the JS dialog sends data) ---

    @Test
    void createResourceViaJsonBody(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = new URL(j.getURL(), "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody("{\"name\":\"json-resource\",\"description\":\"From JSON\",\"labels\":\"l1 l2\"}");

        wc.getPage(req);

        LockableResource resource = LockableResourcesManager.get().fromName("json-resource");
        assertThat("Resource should be created via JSON", resource, is(not(nullValue())));
        assertThat(resource.getDescription(), is("From JSON"));
        assertThat(resource.getLabels(), is("l1 l2"));
    }

    @Test
    void createResourceWithPropertiesViaJson(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = new URL(j.getURL(), "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(
                "{\"name\":\"prop-resource\",\"properties\":[{\"name\":\"key1\",\"value\":\"val1\"},{\"name\":\"key2\",\"value\":\"val2\"}]}");

        wc.getPage(req);

        LockableResource resource = LockableResourcesManager.get().fromName("prop-resource");
        assertThat("Resource should be created", resource, is(not(nullValue())));
        List<LockableResourceProperty> props = resource.getProperties();
        assertThat(props.size(), is(2));
        assertThat(props.get(0).getName(), is("key1"));
        assertThat(props.get(0).getValue(), is("val1"));
        assertThat(props.get(1).getName(), is("key2"));
        assertThat(props.get(1).getValue(), is("val2"));
    }

    // --- Edit endpoint tests ---

    @Test
    void editResourceViaEndpoint(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("edit-me", "old-label");
        LockableResource before = LockableResourcesManager.get().fromName("edit-me");
        assertThat(before.getLabels(), is("old-label"));

        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = new URL(j.getURL(), "lockable-resources/editResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(
                "{\"name\":\"edit-me\",\"description\":\"Updated desc\",\"labels\":\"new-label\",\"properties\":[{\"name\":\"pk\",\"value\":\"pv\"}]}");

        wc.getPage(req);

        LockableResource after = LockableResourcesManager.get().fromName("edit-me");
        assertThat(after.getDescription(), is("Updated desc"));
        assertThat(after.getLabels(), is("new-label"));
        assertThat(after.getProperties().size(), is(1));
        assertThat(after.getProperties().get(0).getName(), is("pk"));
    }

    @Test
    void editNonExistentResourceReturns404(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(j.getURL(), "lockable-resources/editResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody("{\"name\":\"ghost\"}");

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(404));
    }

    @Test
    void editResourceWithoutNameReturns400(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(j.getURL(), "lockable-resources/editResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody("{\"description\":\"no name\"}");

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(400));
    }

    @Test
    void editResourceRejectsFormEncoded(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = new URL(j.getURL(), "lockable-resources/editResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", "whatever"));
        req.setRequestParameters(params);

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(400));
    }

    // --- Permission tests for CRUD ---

    @Test
    void createResourceDeniedWithoutAdminister(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ)
                .everywhere()
                .to("reader"));
        j.jenkins.setCrumbIssuer(null);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("reader");
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setThrowExceptionOnScriptError(false);

        URL url = new URL(j.getURL(), "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", "forbidden-resource"));
        req.setRequestParameters(params);

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(403));
        assertThat("Resource should not be created", LockableResourcesManager.get().fromName("forbidden-resource"), is(nullValue()));
    }

    @Test
    void deleteResourceDeniedWithoutAdminister(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("protected-resource", null);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ)
                .everywhere()
                .to("reader"));
        j.jenkins.setCrumbIssuer(null);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("reader");
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setThrowExceptionOnScriptError(false);

        URL url = new URL(j.getURL(), "lockable-resources/deleteResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("resource", "protected-resource"));
        req.setRequestParameters(params);

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(403));
        assertThat("Resource should still exist", LockableResourcesManager.get().fromName("protected-resource"), is(not(nullValue())));
    }

    // --- UI element tests ---

    @Test
    void editButtonVisibleForFreeResource(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("editable-resource", null);

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        HtmlPage page = wc.goTo("lockable-resources");
        String content = page.getWebResponse().getContentAsString();
        assertThat(content, containsString("lockable-resources-edit-button"));
    }

    @Test
    void tabStructureRendered(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("tab-resource", "some-label");

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        HtmlPage page = wc.goTo("lockable-resources");
        String content = page.getWebResponse().getContentAsString();
        assertThat("Resources tab present", content, containsString("lr-tab-resources"));
        assertThat("Labels tab present", content, containsString("lr-tab-labels"));
        assertThat("Queue tab present", content, containsString("lr-tab-queue"));
        assertThat("Filter toggle present", content, containsString("lr-filter-toggle"));
    }

    @Test
    void configPageShowsNotice(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        HtmlPage page = wc.goTo("manage/configure");
        String content = page.getWebResponse().getContentAsString();
        assertThat("Notice div present", content, containsString("lr-config-moved-notice"));
        assertThat("Link to manage page present", content, containsString("manage/lockable-resources"));
    }

    @Test
    void addResourceButtonHiddenForNonAdmin(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("visible-resource", null);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ, jenkins.model.Jenkins.SYSTEM_READ)
                .everywhere()
                .to("viewer")
                .grant(
                        org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction.VIEW)
                .everywhere()
                .to("viewer"));
        j.jenkins.setCrumbIssuer(null);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("viewer");
        wc.getOptions().setThrowExceptionOnScriptError(false);
        HtmlPage page = wc.goTo("lockable-resources");
        String content = page.getWebResponse().getContentAsString();
        assertThat("Add button should NOT be visible for non-admin", content, not(containsString("lr-add-resource-btn")));
    }
}
