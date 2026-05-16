/* SPDX-License-Identifier: MIT */
package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import hudson.model.ManagementLink;
import org.htmlunit.html.HtmlPage;
import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ManagementLinkTest {

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    @Test
    void managementLinkIsRegistered(JenkinsRule j) {
        LockableResourcesRootAction link = ManagementLink.all().get(LockableResourcesRootAction.class);
        assertThat("ManagementLink should be registered", link, is(not(nullValue())));
        assertThat(link, instanceOf(ManagementLink.class));
    }

    @Test
    void managementLinkCategory(JenkinsRule j) {
        LockableResourcesRootAction link = ManagementLink.all().get(LockableResourcesRootAction.class);
        assertThat(link, is(not(nullValue())));
        assertThat(link.getCategory(), is(ManagementLink.Category.CONFIGURATION));
    }

    @Test
    void managementLinkProperties(JenkinsRule j) {
        LockableResourcesRootAction link = ManagementLink.all().get(LockableResourcesRootAction.class);
        assertThat(link, is(not(nullValue())));
        assertThat(link.getUrlName(), is("lockable-resources"));
        assertThat(link.getIconFileName(), is("symbol-lock-closed"));
        assertThat(link.getDisplayName(), is(not(nullValue())));
        assertThat(link.getDescription(), is(not(nullValue())));
        assertThat(link.getRequiredPermission(), is(LockableResourcesRootAction.VIEW));
    }

    @Test
    void pageAccessibleAtRootUrl(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        HtmlPage page = wc.goTo("lockable-resources");
        assertThat(page.getWebResponse().getStatusCode(), is(200));
    }

    @Test
    void pageAccessibleAtManageUrl(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        HtmlPage page = wc.goTo("manage/lockable-resources");
        assertThat(page.getWebResponse().getStatusCode(), is(200));
    }

    @Test
    void apiEndpointStillWorks(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("test-resource");
        JenkinsRule.WebClient wc = j.createWebClient();
        String json = wc.goTo("lockable-resources/api/json", "application/json")
                .getWebResponse()
                .getContentAsString();
        assertThat(json, containsString("test-resource"));
    }

    @Test
    void manageJenkinsPageShowsLink(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        HtmlPage page = wc.goTo("manage");
        String content = page.getWebResponse().getContentAsString();
        assertThat(content, containsString("lockable-resources"));
    }
}
