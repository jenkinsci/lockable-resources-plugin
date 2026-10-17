package org.jenkins.plugins.lockableresources.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTableDataCell;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class TimestampColumnSortTest {

    @Test
    @Issue("485")
    void sinceColumnExposesSortableTimestamp(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource resource = lrm.fromName("r1");
        resource.reserve("someone");
        Date reservedAt = new Date(1_600_000_000_000L);
        resource.setReservedTimestamp(reservedAt);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnScriptError(false);
        HtmlPage page = wc.goTo("lockable-resources/");

        HtmlTableDataCell since = page.getFirstByXPath("//table[@id='lockable-resources']//td[@data]");
        assertNotNull(since, "Since column should carry a data attribute");
        assertEquals(String.valueOf(reservedAt.getTime()), since.getAttribute("data"));
    }

    @Test
    @Issue("485")
    void buildActionTimestampColumnExposesSortableTimestamp(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("r1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('r1') {}", true));
        WorkflowRun r = j.buildAndAssertSuccess(p);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnScriptError(false);
        HtmlPage page = wc.getPage(r, "locked-resources/");

        List<HtmlTableDataCell> cells = page.getByXPath("//table[@id='used-lockable-resources-by-build']//td[@data]");
        assertFalse(cells.isEmpty(), "timestamp column should carry a data attribute");
        for (HtmlTableDataCell cell : cells) {
            assertTrue(cell.getAttribute("data").matches("\\d+"), cell.getAttribute("data"));
        }
    }
}
