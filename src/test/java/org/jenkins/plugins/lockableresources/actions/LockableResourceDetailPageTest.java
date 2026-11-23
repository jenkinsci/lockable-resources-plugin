package org.jenkins.plugins.lockableresources.actions;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import hudson.model.Result;
import java.net.URL;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The resource detail page at {@code /lockable-resources/resource/NAME/}. */
@WithJenkins
@Issue("1042")
class LockableResourceDetailPageTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
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
                .to("unlocker")
                .grant(Jenkins.READ, LockableResourcesRootAction.CONFIGURE)
                .everywhere()
                .to("configurer")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));
    }

    private JenkinsRule.WebClient as(String user) throws Exception {
        return j.createWebClient().login(user);
    }

    private static List<String> actions(HtmlPage page) {
        return page.querySelectorAll(".lr-resource-detail [data-action]").stream()
                .map(n -> ((DomElement) n).getAttribute("data-action"))
                .toList();
    }

    // ---------------------------------------------------------------------------
    @Test
    void rendersFreeResource() throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("printer-1", "printer floor2");
        LockableResource r = lrm.fromName("printer-1");
        r.setDescription("Laser printer");
        r.setNote("Toner replaced");

        HtmlPage page = as("reader").goTo("lockable-resources/resource/printer-1/");
        String text = page.asNormalizedText();

        assertThat(page.getTitleText(), containsString("printer-1"));
        assertThat(page.querySelector(".lr-resource-detail__status").getTextContent(), is("Free"));
        assertThat(text, containsString("Laser printer"));
        assertThat(text, containsString("floor2"));
        assertThat(text, containsString("Toner replaced"));
        assertThat(text, containsString("No build is waiting for this resource."));
    }

    @Test
    void rendersReservedResourceWithHolderAndReason() throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("printer-1");
        lrm.reserve(List.of(lrm.fromName("printer-1")), "reserver", "maintenance");

        HtmlPage page = as("reader").goTo("lockable-resources/resource/printer-1/");
        String text = page.asNormalizedText();

        assertThat(page.querySelector(".lr-resource-detail__status").getTextContent(), is("Reserved"));
        assertThat(page.getAnchorByText("reserver").getHrefAttribute(), endsWith("/user/reserver"));
        assertThat(text, containsString("maintenance"));
        assertThat(text, containsString(" ago)"));
    }

    @Test
    void rendersLockingBuildAndQueue() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("printer-1", "printer");
        LockableResourcesManager.get().createResourceWithLabel("scanner-1", "scanner");

        WorkflowJob holder = j.createProject(WorkflowJob.class, "holder");
        holder.setDefinition(new CpsFlowDefinition("lock('printer-1') { semaphore 'hold' }", true));
        WorkflowRun held = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold/1", held);

        WorkflowJob byName = j.createProject(WorkflowJob.class, "by-name");
        byName.setDefinition(new CpsFlowDefinition("lock('printer-1') { echo 'got it' }", true));
        WorkflowRun nameWaiter = byName.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", nameWaiter);

        WorkflowJob byLabel = j.createProject(WorkflowJob.class, "by-label");
        byLabel.setDefinition(new CpsFlowDefinition("lock(label: 'printer') { echo 'got it' }", true));
        WorkflowRun labelWaiter = byLabel.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", labelWaiter);

        HtmlPage page = as("reader").goTo("lockable-resources/resource/printer-1/");
        assertThat(page.querySelector(".lr-resource-detail__status").getTextContent(), is("Locked"));
        assertThat(page.getAnchorByText(held.getFullDisplayName()), notNullValue());
        String queue = page.getElementById("lr-resource-queue").asNormalizedText();
        assertThat(queue, containsString("Waiting in queue (2)"));
        assertThat(queue, containsString(nameWaiter.getFullDisplayName()));
        assertThat(queue, containsString(labelWaiter.getFullDisplayName()));

        // neither waiter can use the scanner
        String scanner = as("reader")
                .goTo("lockable-resources/resource/scanner-1/")
                .getElementById("lr-resource-queue")
                .asNormalizedText();
        assertThat(scanner, containsString("Waiting in queue (0)"));

        // the resource is routable now, but the build behind it still checks its own permissions:
        // "reader" has Lockable Resources/View but no Item/Read on the job
        JenkinsRule.WebClient reader = as("reader");
        reader.setThrowExceptionOnFailingStatusCode(false);
        int status = reader.goTo("lockable-resources/resource/printer-1/build/", null)
                .getWebResponse()
                .getStatusCode();
        assertThat(status == 403 || status == 404, is(true));
        assertThat(
                as("admin").goTo("lockable-resources/resource/printer-1/build/").getTitleText(), containsString("#1"));

        SemaphoreStep.success("hold/1", null);
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(held));
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(nameWaiter));
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(labelWaiter));
    }

    @Test
    void worksUnderManageJenkins() throws Exception {
        LockableResourcesManager.get().createResource("printer-1");

        HtmlPage page = as("admin").goTo("manage/lockable-resources/resource/printer-1/");

        assertThat(page.querySelector(".lr-resource-detail__status").getTextContent(), is("Free"));
        // actions and the back link stay inside Manage Jenkins
        DomElement detail = page.querySelector(".lr-resource-detail");
        assertThat(detail.getAttribute("data-lr-base-url"), endsWith("/manage/lockable-resources/"));
        assertThat(
                page.getAnchorByText("Back to Lockable Resources").getHrefAttribute(),
                endsWith("/manage/lockable-resources/"));
    }

    @Test
    void backLinkPointsToMainPage() throws Exception {
        LockableResourcesManager.get().createResource("printer-1");

        HtmlPage page = as("reader").goTo("lockable-resources/resource/printer-1/");
        HtmlPage main = page.getAnchorByText("Back to Lockable Resources").click();

        assertThat(main.getUrl().getPath(), endsWith("/lockable-resources/"));
        assertThat(main.getElementById("lockable-resources"), notNullValue());
    }

    // ---------------------------------------------------------------------------
    @Test
    void unknownResourceIsNotFound() throws Exception {
        JenkinsRule.WebClient wc = as("reader");
        wc.setThrowExceptionOnFailingStatusCode(false);

        for (String url : new String[] {
            "lockable-resources/resource/no-such-resource/", "manage/lockable-resources/resource/no-such-resource/"
        }) {
            assertThat(url, wc.goTo(url, null).getWebResponse().getStatusCode(), is(404));
        }
    }

    @Test
    void userWithoutViewPermissionCannotSeeIt() throws Exception {
        LockableResourcesManager.get().createResource("printer-1");

        JenkinsRule.WebClient nobody = j.createWebClient().withBasicCredentials("nobody");
        nobody.setThrowExceptionOnFailingStatusCode(false);
        Page denied = nobody.goTo("lockable-resources/resource/printer-1/", null);
        assertThat(denied.getWebResponse().getStatusCode(), is(403));
        assertThat(denied.getWebResponse().getContentAsString(), not(containsString("lr-resource-detail")));

        JenkinsRule.WebClient anonymous = j.createWebClient();
        anonymous.setThrowExceptionOnFailingStatusCode(false);
        Page login = anonymous.goTo("lockable-resources/resource/printer-1/", null);
        assertThat(login.getUrl().getPath(), endsWith("/login"));
    }

    // ---------------------------------------------------------------------------
    @Test
    void actionButtonsFollowPermissions() throws Exception {
        LockableResourcesManager.get().createResource("printer-1");
        String url = "lockable-resources/resource/printer-1/";

        HtmlPage reader = as("reader").goTo(url);
        assertThat(actions(reader), is(List.of()));
        assertThat(reader.querySelector(".lockable-resources-edit-button"), nullValue());
        assertThat(reader.getElementById("lr-add-resource-template"), nullValue());

        assertThat(actions(as("reserver").goTo(url)), is(List.of("reserve")));

        HtmlPage configurer = as("configurer").goTo(url);
        // no delete on the detail page: there would be nothing left to show
        assertThat(actions(configurer), is(List.of("disable")));
        assertThat(configurer.querySelector(".lockable-resources-edit-button"), notNullValue());
        assertThat(configurer.getElementById("lr-add-resource-template"), notNullValue());
    }

    @Test
    void actionButtonsFollowState() throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("printer-1");
        lrm.reserve(List.of(lrm.fromName("printer-1")), "someone-else", null);
        String url = "lockable-resources/resource/printer-1/";

        // reserved by somebody else: only an admin may unreserve, STEAL may reassign
        assertThat(actions(as("reserver").goTo(url)), is(List.of()));
        assertThat(actions(as("unlocker").goTo(url)), is(List.of("reassign")));
        assertThat(actions(as("admin").goTo(url)), is(List.of("unreserve", "reassign", "disable")));
    }

    @Test
    void actionPostsToRootActionAndReturnsToDetailPage() throws Exception {
        LockableResourcesManager.get().createResource("printer-1");
        JenkinsRule.WebClient wc = as("admin");
        HtmlPage page = wc.goTo("lockable-resources/resource/printer-1/");
        String base = ((DomElement) page.querySelector(".lr-resource-detail")).getAttribute("data-lr-base-url");

        // what the shared JS does for "unlock"/"reserve": POST to the root action's do* endpoint
        WebRequest post = new WebRequest(new URL(j.getURL(), base + "reserve?resource=printer-1"), HttpMethod.POST);
        post.setAdditionalHeader("Referer", page.getUrl().toString());
        wc.addCrumb(post);
        Page after = wc.getPage(post);

        assertThat(after.getWebResponse().getStatusCode(), is(200));
        assertThat(after.getUrl().getPath(), endsWith("/lockable-resources/resource/printer-1/"));
        assertThat(LockableResourcesManager.get().fromName("printer-1").getReservedBy(), is("admin"));
    }

    // ---------------------------------------------------------------------------
    /**
     * Follows a link the way a browser resolves it. HtmlUnit does not click links inside the table, which sits in
     * a tab that page JS reveals; navigating to the resolved href checks the same thing: the link leads to the page.
     */
    private static HtmlPage follow(JenkinsRule.WebClient wc, HtmlAnchor link) throws Exception {
        HtmlPage page = (HtmlPage) link.getPage();
        return wc.getPage(page.getFullyQualifiedUrl(link.getHrefAttribute()));
    }

    @Test
    void mainTableLinksToDetailPage() throws Exception {
        LockableResourcesManager.get().createResource("printer-1");

        for (String main : new String[] {"lockable-resources/", "manage/lockable-resources/"}) {
            JenkinsRule.WebClient wc = as("admin");
            HtmlAnchor link = wc.goTo(main).getAnchorByHref("resource/printer-1/");
            HtmlPage detail = follow(wc, link);

            assertThat(detail.getUrl().getPath(), endsWith("/" + main + "resource/printer-1/"));
            assertThat(detail.querySelector(".lr-resource-detail"), notNullValue());
        }
    }

    @Test
    void nameIsUrlEncodedAndUserInputEscaped() throws Exception {
        String name = "lab printer #2 & co";
        LockableResourcesManager.get().createResource(name);
        LockableResource r = LockableResourcesManager.get().fromName(name);
        r.setDescription("<img src=x onerror=alert(1)>");
        r.setLabels("<b>bold</b>");

        JenkinsRule.WebClient wc = as("reader");
        HtmlPage main = wc.goTo("lockable-resources/");
        HtmlPage detail = follow(wc, main.getAnchorByHref("resource/lab%20printer%20%232%20&%20co/"));

        assertThat(
                ((DomElement) detail.querySelector(".lr-resource-detail")).getAttribute("data-resource-name"),
                is(name));
        String html = detail.getWebResponse().getContentAsString();
        assertThat(html, not(containsString("<img src=x")));
        assertThat(html, not(containsString("<b>bold</b>")));
        assertThat(detail.asNormalizedText(), containsString("<img src=x onerror=alert(1)>"));
    }
}
