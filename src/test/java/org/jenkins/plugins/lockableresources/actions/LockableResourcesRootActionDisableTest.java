/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.actions;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import hudson.model.Result;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Disable and enable a resource from the Lockable Resources page. */
@WithJenkins
class LockableResourcesRootActionDisableTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        // to speed up the test
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.READ, LockableResourcesRootAction.RESERVE)
                .everywhere()
                .to("reserver")
                .grant(Jenkins.READ, LockableResourcesRootAction.UNLOCK, LockableResourcesRootAction.STEAL)
                .everywhere()
                .to("stealer")
                .grant(Jenkins.READ, LockableResourcesRootAction.CONFIGURE)
                .everywhere()
                .to("configurer")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));
    }

    // ---------------------------------------------------------------------------
    private static LockableResource resource(String name) {
        return LockableResourcesManager.get().fromName(name);
    }

    private JenkinsRule.WebClient webClient(String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        if (user != null) {
            wc.login(user);
        }
        return wc;
    }

    /** POSTs to an action of the root action, the same way the table buttons do. */
    private WebResponse post(String user, String action, String query) throws Exception {
        return request(user, HttpMethod.POST, action, query);
    }

    private WebResponse request(String user, HttpMethod method, String action, String query) throws Exception {
        JenkinsRule.WebClient wc = webClient(user);
        WebRequest req = new WebRequest(new URL(j.getURL(), "lockable-resources/" + action + "?" + query), method);
        if (method == HttpMethod.POST) {
            wc.addCrumb(req);
        }
        return wc.loadWebResponse(req);
    }

    private static String param(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private WebResponse disable(String user, String name, String cause) throws Exception {
        return post(user, "disable", "resource=" + param(name) + "&cause=" + param(cause));
    }

    private WebResponse enable(String user, String name) throws Exception {
        return post(user, "enable", "resource=" + param(name));
    }

    private DomElement row(String user, String name) throws Exception {
        HtmlPage page = webClient(user).goTo("lockable-resources");
        DomElement row = page.querySelector("#lockable-resources tr[data-resource-name='" + name + "']");
        assertThat("row of " + name, row, notNullValue());
        return row;
    }

    /** Text of the row; the resources tab is hidden without JavaScript, so this is not the visible text. */
    private static String text(DomElement row) {
        return row.getTextContent().replaceAll("\\s+", " ");
    }

    private static boolean hasButton(DomElement row, String action) {
        return row.querySelector("button[data-action='" + action + "']") != null;
    }

    // ---------------------------------------------------------------------------
    @Test
    void disableAndEnable() throws Exception {
        LockableResourcesManager.get().createResource("r1");

        WebResponse rsp = disable("configurer", "r1", "  printer jam  ");
        assertThat(rsp.getContentAsString(), rsp.getStatusCode(), is(200));
        assertThat(resource("r1").getDisabledCause(), is("printer jam"));

        // disabling again replaces the cause
        assertThat(disable("configurer", "r1", "toner empty").getStatusCode(), is(200));
        assertThat(resource("r1").getDisabledCause(), is("toner empty"));

        assertThat(enable("configurer", "r1").getStatusCode(), is(200));
        assertThat(resource("r1").isDisabled(), is(false));

        // enabling an enabled resource does nothing
        assertThat(enable("configurer", "r1").getStatusCode(), is(200));
        assertThat(resource("r1").isDisabled(), is(false));
    }

    @Test
    void disableRequiresCause() throws Exception {
        LockableResourcesManager.get().createResource("r1");

        assertThat(disable("configurer", "r1", "   ").getStatusCode(), is(400));
        assertThat(post("configurer", "disable", "resource=r1").getStatusCode(), is(400));
        assertThat(resource("r1").isDisabled(), is(false));
    }

    @Test
    void unknownResource() throws Exception {
        assertThat(disable("configurer", "missing", "broken").getStatusCode(), is(404));
        assertThat(enable("configurer", "missing").getStatusCode(), is(404));
    }

    @Test
    void disableAndEnableNeedConfigurePermission() throws Exception {
        LockableResourcesManager.get().createResource("r1");
        LockableResourcesManager.get().createResource("r2");
        resource("r2").setDisabledCause("broken");

        for (String user : List.of("reader", "reserver", "stealer")) {
            assertThat(user, disable(user, "r1", "broken").getStatusCode(), is(403));
            assertThat(user, enable(user, "r2").getStatusCode(), is(403));
        }
        // anonymous
        disable(null, "r1", "broken");
        enable(null, "r2");

        assertThat(resource("r1").isDisabled(), is(false));
        assertThat(resource("r2").getDisabledCause(), is("broken"));

        // administrators have every permission
        assertThat(disable("admin", "r1", "broken").getStatusCode(), is(200));
        assertThat(enable("admin", "r2").getStatusCode(), is(200));
        assertThat(resource("r1").isDisabled(), is(true));
        assertThat(resource("r2").isDisabled(), is(false));
    }

    @Test
    void disableAndEnableRequirePost() throws Exception {
        LockableResourcesManager.get().createResource("r1");

        WebResponse rsp = request("configurer", HttpMethod.GET, "disable", "resource=r1&cause=broken");
        assertThat(rsp.getStatusCode(), is(405));
        assertThat(resource("r1").isDisabled(), is(false));

        resource("r1").setDisabledCause("broken");
        rsp = request("configurer", HttpMethod.GET, "enable", "resource=r1");
        assertThat(rsp.getStatusCode(), is(405));
        assertThat(resource("r1").isDisabled(), is(true));
    }

    // ---------------------------------------------------------------------------
    @Test
    void disabledResourceCanNotBeReserved() throws Exception {
        LockableResourcesManager.get().createResource("r1");
        assertThat(disable("configurer", "r1", "printer jam").getStatusCode(), is(200));

        WebResponse rsp = post("reserver", "reserve", "resource=r1&reason=testing");
        assertThat(rsp.getStatusCode(), is(423));
        assertThat(rsp.getContentAsString(), containsString("Resource r1 is disabled: printer jam"));
        assertThat(resource("r1").getReservedBy(), is(nullValue()));

        // the manager refuses too, for other callers
        assertThat(LockableResourcesManager.get().reserve(List.of(resource("r1")), "someone"), is(false));
        assertThat(resource("r1").getReservedBy(), is(nullValue()));

        // once enabled it can be reserved again
        assertThat(enable("configurer", "r1").getStatusCode(), is(200));
        assertThat(post("reserver", "reserve", "resource=r1").getStatusCode(), is(200));
        assertThat(resource("r1").getReservedBy(), is("reserver"));
    }

    @Test
    void disabledResourceCanNotBeReassigned() throws Exception {
        LockableResourcesManager.get().createResource("r1");
        LockableResourcesManager.get().reserve(List.of(resource("r1")), "someone");
        assertThat(disable("configurer", "r1", "printer jam").getStatusCode(), is(200));

        WebResponse rsp = post("stealer", "reassign", "resource=r1");
        assertThat(rsp.getStatusCode(), is(423));
        assertThat(rsp.getContentAsString(), containsString("Resource r1 is disabled: printer jam"));
        assertThat(resource("r1").getReservedBy(), is("someone"));

        LockableResourcesManager.get().reassign(List.of(resource("r1")), "other");
        assertThat(resource("r1").getReservedBy(), is("someone"));
    }

    @Test
    void disabledLockedResourceCanNotBeStolen() throws Exception {
        LockableResourcesManager.get().createResource("r1");
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('r1') { semaphore 'wait-inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b);

        // a build that holds the resource keeps it when it is disabled
        assertThat(disable("configurer", "r1", "printer jam").getStatusCode(), is(200));
        assertThat(resource("r1").isLocked(), is(true));

        WebResponse rsp = post("stealer", "steal", "resource=r1&reason=mine");
        assertThat(rsp.getStatusCode(), is(423));
        assertThat(rsp.getContentAsString(), containsString("Resource r1 is disabled: printer jam"));
        assertThat(resource("r1").isLocked(), is(true));
        assertThat(resource("r1").getReservedBy(), is(nullValue()));

        assertThat(LockableResourcesManager.get().steal(List.of(resource("r1")), "someone"), is(false));
        assertThat(resource("r1").isLocked(), is(true));

        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertThat(resource("r1").isDisabled(), is(true));
    }

    // ---------------------------------------------------------------------------
    @Test
    void tableShowsDisabledResource() throws Exception {
        LockableResourcesManager.get().createResource("r1");
        LockableResourcesManager.get().createResource("r2");
        assertThat(disable("configurer", "r1", "printer <b>jam</b>").getStatusCode(), is(200));

        DomElement row = row("configurer", "r1");
        assertThat(row.getAttribute("data-resource-status"), is("disabled"));
        String text = text(row);
        assertThat(text, containsString("DISABLED"));
        assertThat(text, not(containsString("FREE")));
        // the cause is shown, escaped
        assertThat(text, containsString("Disabled: printer <b>jam</b>"));
        assertThat(row.querySelector("b"), is(nullValue()));
        assertThat(hasButton(row, "enable"), is(true));
        assertThat(hasButton(row, "disable"), is(false));

        DomElement enabledRow = row("configurer", "r2");
        assertThat(enabledRow.getAttribute("data-resource-status"), is("free"));
        assertThat(hasButton(enabledRow, "disable"), is(true));
        assertThat(hasButton(enabledRow, "enable"), is(false));

        // no reserve button on a disabled resource, and no disable/enable without CONFIGURE
        DomElement reserverRow = row("reserver", "r1");
        assertThat(hasButton(reserverRow, "reserve"), is(false));
        assertThat(hasButton(reserverRow, "enable"), is(false));
        assertThat(hasButton(row("reserver", "r2"), "reserve"), is(true));
        assertThat(hasButton(row("reserver", "r2"), "disable"), is(false));
    }

    @Test
    void tableShowsDisabledLockedResource() throws Exception {
        LockableResourcesManager.get().createResource("r1");
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('r1') { semaphore 'wait-inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b);
        assertThat(disable("configurer", "r1", "printer jam").getStatusCode(), is(200));

        DomElement row = row("stealer", "r1");
        assertThat(row.getAttribute("data-resource-status"), is("locked"));
        String text = text(row);
        assertThat(text, containsString("LOCKED"));
        assertThat(text, containsString("Disabled: printer jam"));
        assertThat(row.querySelector(".lr-disabled-label"), notNullValue());
        // unlocking is still possible, stealing is not
        assertThat(hasButton(row, "unlock"), is(true));
        assertThat(hasButton(row, "steal"), is(false));

        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
    }

    @Test
    void countsDoNotTreatDisabledAsFree() throws Exception {
        LockableResourcesManager.get().createResource("r1");
        LockableResourcesManager.get().createResource("r2");
        resource("r1").setLabels("printer");
        resource("r2").setLabels("printer");
        assertThat(disable("configurer", "r1", "printer jam").getStatusCode(), is(200));

        LockableResourcesRootAction action = new LockableResourcesRootAction();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            LockableResourcesRootAction.Summary summary = action.getSummary();
            assertThat(summary.getTotal(), is(2));
            assertThat(summary.getFree(), is(1));
            assertThat(summary.getDisabled(), is(1));

            LockableResourcesRootAction.LockableResourcesLabel label =
                    action.getLabelsList().get("printer");
            assertThat(label.getAssigned(), is(2));
            assertThat(label.getAvailable(), is(1));
            assertThat(label.getAvailablePercentage(), is(50));
            // the existing counts are unchanged
            assertThat(label.getFree(), is(2));
            assertThat(action.getFreeResourceAmount("printer"), is(2));
            assertThat(LockableResourcesManager.get().getFreeResourceAmount("printer"), is(2));
        }

        HtmlPage page = webClient("reader").goTo("lockable-resources");
        assertThat(page.querySelector(".lr-legend-item--disabled"), notNullValue());
    }

    // ---------------------------------------------------------------------------
    @Test
    void enableUnblocksWaitingBuild() throws Exception {
        LockableResourcesManager.get().createResource("r1");
        assertThat(disable("configurer", "r1", "printer jam").getStatusCode(), is(200));

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('r1') { echo 'inside lock' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: r1] is not free, waiting for execution ...", b);
        j.assertLogNotContains("inside lock", b);

        assertThat(enable("configurer", "r1").getStatusCode(), is(200));

        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(b));
        j.assertLogContains("inside lock", b);
        // the resource was created on the fly (ephemeral), so it is removed once the enabled resource is released
        assertThat(resource("r1"), is(nullValue()));
    }
}
