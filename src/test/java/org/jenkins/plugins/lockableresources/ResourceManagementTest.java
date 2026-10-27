/* SPDX-License-Identifier: MIT */
package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ResourceManagementTest {

    private static URL url(JenkinsRule j, String path) throws Exception {
        URI base = j.getURL().toURI();
        return base.resolve(path).toURL();
    }

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

    private WorkflowJob enqueueQueuedBuilds(JenkinsRule j, String resourceName, String jobName, int count)
            throws Exception {
        LockableResource resource = LockableResourcesManager.get().fromName(resourceName);
        LockableResourcesManager.get().reserve(Collections.singletonList(resource), "queue-admin");

        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, jobName);
        job.setDefinition(new CpsFlowDefinition("""
                lock('%s') {
                    echo('inside queue test')
                }
                """.formatted(resourceName), true));

        for (int i = 0; i < count; i++) {
            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.waitForMessage("[Resource: " + resourceName + "] is not free, waiting for execution ...", run);
        }

        return job;
    }

    private JSONObject getQueuePage(JenkinsRule j, JenkinsRule.WebClient wc, String query) throws Exception {
        String path = "lockable-resources/getQueuePage" + (query == null || query.isEmpty() ? "" : "?" + query);
        URL url = url(j, path);
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        return JSONObject.fromObject(wc.getPage(req).getWebResponse().getContentAsString());
    }

    private JSONObject getResourcesByLabelExpression(JenkinsRule j, JenkinsRule.WebClient wc, String expr)
            throws Exception {
        String query = expr == null ? "" : "expr=" + URLEncoder.encode(expr, StandardCharsets.UTF_8);
        String path = "lockable-resources/getResourcesByLabelExpression" + (query.isEmpty() ? "" : "?" + query);
        URL url = url(j, path);
        WebRequest req = new WebRequest(url, HttpMethod.GET);
        return JSONObject.fromObject(wc.getPage(req).getWebResponse().getContentAsString());
    }

    @Test
    void createResourceViaEndpoint(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = url(j, "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, org.htmlunit.HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", "test-resource-1"));
        params.add(new NameValuePair("description", "A test resource"));
        params.add(new NameValuePair("labels", "label1 label2"));
        params.add(new NameValuePair("group", "release"));
        req.setRequestParameters(params);

        wc.getPage(req);

        LockableResource resource = LockableResourcesManager.get().fromName("test-resource-1");
        assertThat("Resource should be created", resource, is(not(nullValue())));
        assertThat(resource.getDescription(), is("A test resource"));
        assertThat(resource.getLabelsAsString(), is("label1 label2"));
        assertThat(resource.getGroup(), is("release"));
    }

    @Test
    void createResourceWithNameOnly(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = url(j, "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, org.htmlunit.HttpMethod.POST);
        req.setAdditionalHeader("Referer", url(j, "lockable-resources").toString());
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", "minimal-resource"));
        req.setRequestParameters(params);

        wc.getPage(req);

        LockableResource resource = LockableResourcesManager.get().fromName("minimal-resource");
        assertThat("Resource should be created", resource, is(not(nullValue())));
        assertThat(resource.getGroup(), is("common"));
    }

    @Test
    void createDuplicateResourceReturns409(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("existing-resource", null);

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = url(j, "lockable-resources/createResource");
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

        URL url = url(j, "lockable-resources/createResource");
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

        URL url = url(j, "lockable-resources/deleteResource");
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

        URL url = url(j, "lockable-resources/deleteResource");
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

        URL url = url(j, "lockable-resources/deleteResource");
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

        URL url = url(j, "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(
                "{\"name\":\"json-resource\",\"description\":\"From JSON\",\"labels\":\"l1 l2\",\"group\":\"release\"}");

        wc.getPage(req);

        LockableResource resource = LockableResourcesManager.get().fromName("json-resource");
        assertThat("Resource should be created via JSON", resource, is(not(nullValue())));
        assertThat(resource.getDescription(), is("From JSON"));
        assertThat(resource.getLabelsAsString(), is("l1 l2"));
        assertThat(resource.getGroup(), is("release"));
    }

    @Test
    void createResourceWithPropertiesViaJson(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = url(j, "lockable-resources/createResource");
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
        assertThat(before.getLabelsAsString(), is("old-label"));

        JenkinsRule.WebClient wc = loginAsAdmin(j);

        URL url = url(j, "lockable-resources/editResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(
                "{\"name\":\"edit-me\",\"description\":\"Updated desc\",\"labels\":\"new-label\",\"group\":\"release\",\"properties\":[{\"name\":\"pk\",\"value\":\"pv\"}]}");

        wc.getPage(req);

        LockableResource after = LockableResourcesManager.get().fromName("edit-me");
        assertThat(after.getDescription(), is("Updated desc"));
        assertThat(after.getLabelsAsString(), is("new-label"));
        assertThat(after.getProperties().size(), is(1));
        assertThat(after.getProperties().get(0).getName(), is("pk"));
        assertThat(after.getGroup(), is("release"));
    }

    @Test
    void editingWithoutGroupPreservesMembershipAndEmptyGroupRestoresCommon(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("device");
        LockableResource resource = LockableResourcesManager.get().fromName("device");
        resource.setEphemeral(false);
        resource.setGroup("release");
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        WebRequest req = new WebRequest(url(j, "lockable-resources/editResource"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody("{\"name\":\"device\",\"description\":\"updated\"}");
        wc.getPage(req);
        assertThat(resource.getGroup(), is("release"));

        req.setRequestBody("{\"name\":\"device\",\"group\":\"  \"}");
        wc.getPage(req);
        assertThat(resource.getGroup(), is("common"));
    }

    @Test
    void groupTabsAndResourceMembershipAreRendered(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.createResource("ordinary-device");
        manager.createResource("release-device");
        manager.fromName("release-device").setGroup("release");
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        HtmlPage page = wc.goTo("lockable-resources");

        assertThat(
                page.getByXPath("//div[@id='lr-tab-resources']//button[@data-lr-group]")
                        .size(),
                is(3));
        assertThat(
                page.getByXPath("//div[@id='lr-tab-labels']//button[@data-lr-group]")
                        .size(),
                is(3));
        assertThat(
                page.<DomElement>getFirstByXPath("//button[@data-lr-group='']")
                        .getTextContent()
                        .trim(),
                is("All (2)"));
        assertThat(
                page.<DomElement>getFirstByXPath("//button[@data-lr-group='common']")
                        .getTextContent()
                        .trim(),
                is("common (1)"));
        assertThat(
                page.<DomElement>getFirstByXPath("//button[@data-lr-group='release']")
                        .getTextContent()
                        .trim(),
                is("release (1)"));
        assertThat(page.getByXPath("//tr[@data-resource-group='release']").size(), is(1));
        assertThat(page.getWebResponse().getContentAsString(), containsString("name=\"group\""));
    }

    @Test
    void labelsShowGroupScopedCountsAndAvailability(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setResourceGroups(List.of(new ResourceGroup("empty")));
        manager.createResourceWithLabel("common-device", "shared common-only");
        manager.createResourceWithLabel("release-free", "shared release-only");
        manager.createResourceWithLabel("release-reserved", "shared");
        manager.fromName("release-free").setGroup("release");
        manager.fromName("release-reserved").setGroup("release");
        manager.reserve(List.of(manager.fromName("release-reserved")), "admin");
        var groups = new LockableResourcesRootAction().getLabelsByGroup();
        assertThat(groups.get("").get("shared").getAssigned(), is(3));
        assertThat(groups.get("").get("shared").getFree(), is(2));
        assertThat(groups.get("common").get("shared").getAssigned(), is(1));
        assertThat(groups.get("common").get("shared").getFree(), is(1));
        assertThat(groups.get("release").get("shared").getAssigned(), is(2));
        assertThat(groups.get("release").get("shared").getFree(), is(1));
        assertThat(groups.get("release").get("shared").getPercentage(), is(50));
        assertThat(groups.get("release").containsKey("common-only"), is(false));
        assertThat(groups.get("empty").isEmpty(), is(true));
        HtmlPage page = loginAsAdmin(j).goTo("lockable-resources");
        assertThat(
                page.<DomElement>getFirstByXPath(
                                "//tr[@data-label-name='shared' and @data-label-group='release']/td[2]")
                        .getTextContent()
                        .trim(),
                is("2"));
        assertThat(
                page.<DomElement>getFirstByXPath(
                                "//tr[@data-label-name='shared' and @data-label-group='release']/td[3]")
                        .getTextContent()
                        .trim(),
                is("1"));
        assertThat(
                page.getByXPath("//div[@id='lr-tab-labels']//button[@data-lr-group='empty']")
                        .size(),
                is(1));
    }

    @Test
    void resourceTableRendersDisplayIndexesAndGroupBadges(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        for (String group : List.of("common", "plc", "release")) {
            for (int i = 1; i <= 2; i++) {
                String name = group + "-" + i;
                manager.createResource(name);
                manager.fromName(name).setGroup(group);
            }
        }
        assertThat(manager.reserve(List.of(manager.fromName("common-1")), "admin"), is(true));
        HtmlPage page = loginAsAdmin(j).goTo("lockable-resources");
        List<String> columnClasses = List.of(
                "lr-col-select",
                "lr-col-index",
                "lr-col-resource",
                "lr-col-status",
                "lr-col-held-by",
                "lr-col-reason",
                "lr-col-since",
                "lr-col-labels",
                "lr-col-properties",
                "lr-col-actions");
        for (int column = 0; column < columnClasses.size(); column++) {
            assertThat(
                    page.<DomElement>getFirstByXPath(
                                    "//table[@id='lockable-resources']/thead/tr/th[" + (column + 1) + "]")
                            .getAttribute("class"),
                    containsString(columnClasses.get(column)));
        }
        assertThat(
                page.<DomElement>getFirstByXPath("//table[@id='lockable-resources']/thead/tr/th[2]")
                        .getAttribute("data-sort-disable"),
                is("true"));
        int index = 1;
        for (String group : List.of("common", "plc", "release")) {
            for (int i = 1; i <= 2; i++) {
                DomElement row = page.getFirstByXPath("//tr[@data-resource-name='" + group + "-" + i + "']");
                for (int column = 0; column < columnClasses.size(); column++) {
                    assertThat(
                            row.<DomElement>getFirstByXPath("td[" + (column + 1) + "]")
                                    .getAttribute("class"),
                            containsString(columnClasses.get(column)));
                }
                DomElement indexCell = row.getFirstByXPath("td[2]");
                assertThat(indexCell.getAttribute("class"), containsString("lr-resource-index"));
                assertThat(indexCell.getTextContent(), is(String.valueOf(index++)));
                assertThat(
                        row.<DomElement>getFirstByXPath("td[3]/div[@class='lr-resource-group']")
                                .getTextContent(),
                        is(group));
            }
        }
    }

    @Test
    void editingGroupWakesWaitingRequests(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.createResourceWithLabel("ordinary", "device");
        manager.reserve(List.of(manager.fromName("ordinary")), "maintenance");
        manager.createResourceWithLabel("replacement", "device");
        manager.fromName("replacement").setEphemeral(false);
        manager.fromName("replacement").setGroup("release");
        WorkflowJob job = j.createProject(WorkflowJob.class, "waiting-for-device");
        job.setDefinition(new CpsFlowDefinition("""
                lock(label: 'device', quantity: 1, variable: 'DEVICE') {
                  echo "selected=${env.DEVICE}"
                }
                """, true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("waiting for execution", run);

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        WebRequest req = new WebRequest(url(j, "lockable-resources/editResource"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody("{\"name\":\"replacement\",\"labels\":\"device\",\"group\":\"common\"}");
        wc.getPage(req);

        j.assertBuildStatusSuccess(j.waitForCompletion(run));
        j.assertLogContains("selected=replacement", run);
        assertThat(manager.fromName("ordinary").isReserved(), is(true));
    }

    @Test
    void ephemeralResourceCannotBeMovedIntoCustomGroup(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.createResource("temporary-device");
        LockableResource resource = manager.fromName("temporary-device");
        resource.setEphemeral(true);
        resource.setDescription("original");
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        WebRequest req = new WebRequest(url(j, "lockable-resources/editResource"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody("{\"name\":\"temporary-device\",\"description\":\"changed\",\"group\":\"release\"}");

        assertThat(wc.getPage(req).getWebResponse().getStatusCode(), is(400));
        assertThat(resource.getGroup(), is("common"));
        assertThat(resource.getDescription(), is("original"));
    }

    @Test
    void editNonExistentResourceReturns404(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        URL url = url(j, "lockable-resources/editResource");
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

        URL url = url(j, "lockable-resources/editResource");
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

        URL url = url(j, "lockable-resources/editResource");
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

        URL url = url(j, "lockable-resources/createResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", "forbidden-resource"));
        req.setRequestParameters(params);

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(403));
        assertThat(
                "Resource should not be created",
                LockableResourcesManager.get().fromName("forbidden-resource"),
                is(nullValue()));
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

        URL url = url(j, "lockable-resources/deleteResource");
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("resource", "protected-resource"));
        req.setRequestParameters(params);

        var response = wc.getPage(req).getWebResponse();
        assertThat(response.getStatusCode(), is(403));
        assertThat(
                "Resource should still exist",
                LockableResourcesManager.get().fromName("protected-resource"),
                is(not(nullValue())));
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
        assertThat("Global search toggle present", content, containsString("lr-global-search-toggle"));
        assertThat("Reset filters button present", content, containsString("lr-reset-filters-btn"));
        assertThat("Column visibility toggle present", content, containsString("lr-col-visibility-toggle"));
    }

    @Test
    void overviewAndBulkActionsRendered(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("overview-resource", "some-label");

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        HtmlPage page = wc.goTo("lockable-resources");
        String content = page.getWebResponse().getContentAsString();

        assertThat("Overview tab present", content, containsString("lr-tab-overview"));
        assertThat("Overview grid present", content, containsString("lr-overview-grid"));
        assertThat("Bulk actions bar present", content, containsString("lr-bulk-bar"));
    }

    @Test
    void queuePageEndpointReturnsPagedJson(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("queue-page-resource", null);
        WorkflowJob job = enqueueQueuedBuilds(j, "queue-page-resource", "queue-page-job", 3);

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        JSONObject response = getQueuePage(j, wc, "page=1&size=2");

        assertThat(response.getInt("page"), is(1));
        assertThat(response.getInt("size"), is(2));
        assertThat(response.getInt("total"), is(3));
        assertThat(response.getInt("pages"), is(2));

        JSONArray items = response.getJSONArray("items");
        assertThat(items.size(), is(2));
        assertThat(items.getJSONObject(0).getString("type"), is("resources"));
        assertThat(items.getJSONObject(0).getString("requestText"), containsString("queue-page-resource"));
        assertThat(items.getJSONObject(0).getString("requestedBy"), containsString(job.getFullName()));
    }

    @Test
    void queuePageEndpointAppliesServerSideFilters(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("queue-filter-resource", null);
        enqueueQueuedBuilds(j, "queue-filter-resource", "queue-filter-job", 3);

        JenkinsRule.WebClient wc = loginAsAdmin(j);

        JSONObject byRequest = getQueuePage(j, wc, "request=queue-filter-resource");
        assertThat(byRequest.getInt("total"), is(3));

        JSONObject byRequestedBy = getQueuePage(j, wc, "requestedBy=%232");
        assertThat(byRequestedBy.getInt("total"), is(1));
        assertThat(byRequestedBy.getJSONArray("items").getJSONObject(0).getString("requestedBy"), containsString("#2"));

        JSONObject combined = getQueuePage(j, wc, "type=resources&filter=queue-filter-resource");
        assertThat(combined.getInt("total"), is(3));
        assertThat(combined.getJSONArray("items").getJSONObject(0).getString("type"), is("resources"));
    }

    @Test
    void resourcesByLabelExpressionEndpointAppliesExpression(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("expr-r1", "A B");
        LockableResourcesManager.get().createResourceWithLabel("expr-r2", "A");
        LockableResourcesManager.get().createResourceWithLabel("expr-r3", "B C");

        JenkinsRule.WebClient wc = loginAsAdmin(j);
        JSONObject response = getResourcesByLabelExpression(j, wc, "A && B");

        JSONArray items = response.getJSONArray("items");
        List<String> names = new ArrayList<>();
        for (Object o : items) {
            names.add(((JSONObject) o).getString("name"));
        }

        assertThat(response.getInt("total"), is(1));
        assertThat(names.contains("expr-r1"), is(true));
        assertThat(names.contains("expr-r2"), is(false));
        assertThat(names.contains("expr-r3"), is(false));
    }

    @Test
    void configPageShowsNotice(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = loginAsAdmin(j);
        HtmlPage page = wc.goTo("manage/configure");
        String content = page.getWebResponse().getContentAsString();
        assertThat("Notice div present", content, containsString("lr-config-moved-notice"));
        assertThat("Link to manage page present", content, containsString("lockable-resources"));
    }

    @Test
    void addResourceButtonHiddenForNonAdmin(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("visible-resource", null);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ, jenkins.model.Jenkins.SYSTEM_READ)
                .everywhere()
                .to("viewer")
                .grant(org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction.VIEW)
                .everywhere()
                .to("viewer"));
        j.jenkins.setCrumbIssuer(null);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("viewer");
        wc.getOptions().setThrowExceptionOnScriptError(false);
        HtmlPage page = wc.goTo("lockable-resources");
        String content = page.getWebResponse().getContentAsString();
        assertThat(
                "Add button should NOT be visible for non-admin", content, not(containsString("lr-add-resource-btn")));
    }

    @Test
    void pageAccessibleWithViewPermissionOnly(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("perm-test-resource", null);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ)
                .everywhere()
                .to("viewuser")
                .grant(org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction.VIEW)
                .everywhere()
                .to("viewuser"));
        j.jenkins.setCrumbIssuer(null);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("viewuser");
        wc.getOptions().setThrowExceptionOnScriptError(false);
        HtmlPage page = wc.goTo("lockable-resources");
        assertThat(
                "Page should be accessible with VIEW permission",
                page.getWebResponse().getStatusCode(),
                is(200));
    }

    @Test
    void pageAccessibleWithJenkinsReadOnly(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ)
                .everywhere()
                .to("readuser"));
        j.jenkins.setCrumbIssuer(null);

        // Jenkins.READ implies Lockable Resources/View, so the page should load
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("readuser");
        wc.getOptions().setThrowExceptionOnScriptError(false);
        HtmlPage page = wc.goTo("lockable-resources");
        assertThat(
                "Jenkins.READ should imply VIEW and allow page access",
                page.getWebResponse().getStatusCode(),
                is(200));
    }

    @Test
    void pageDeniedWithoutJenkinsRead(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy());
        j.jenkins.setCrumbIssuer(null);

        // Anonymous user with no READ cannot access the page
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setThrowExceptionOnScriptError(false);
        HtmlPage page = wc.goTo("lockable-resources");
        // Jenkins returns 403 with a meta-refresh redirect to the login page.
        // HtmlUnit follows the meta-refresh, so check either 403 or login redirect.
        int status = page.getWebResponse().getStatusCode();
        String url = page.getUrl().toString();
        assertThat(
                "User without Jenkins.READ should be denied (got 403 or redirected to login)",
                status == 403 || url.contains("login"),
                is(true));
    }
}
