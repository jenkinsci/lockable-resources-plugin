package org.jenkins.plugins.lockableresources.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockableResourcesRootActionLoginRedirectTest {

    @Test
    @Issue("952")
    void anonymousWithoutPermissionsIsRedirectedToLogin(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy());

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.setThrowExceptionOnFailingStatusCode(false);

        for (String url : new String[] {"lockable-resources/", "manage/"}) {
            Page page = wc.goTo(url, null);
            assertEquals(200, page.getWebResponse().getStatusCode(), url);
            assertTrue(page.getUrl().getPath().endsWith("/login"), url + " -> " + page.getUrl());
            assertTrue(page.getWebResponse().getContentAsString().contains("j_username"), url);
        }
    }

    @Test
    @Issue("952")
    void anonymousWithReadCanViewPage(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().toEveryone());

        JenkinsRule.WebClient wc = j.createWebClient();
        Page page = wc.goTo("lockable-resources/");
        assertEquals(200, page.getWebResponse().getStatusCode());
        assertTrue(
                page.getUrl().getPath().endsWith("/lockable-resources/"),
                page.getUrl().toString());
    }

    @Test
    @Issue("952")
    void loggedInUserWithoutPermissionsGetsForbiddenNotNotFound(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy());

        JenkinsRule.WebClient wc = j.createWebClient().withBasicCredentials("nobody");
        wc.setThrowExceptionOnFailingStatusCode(false);

        for (String url : new String[] {"lockable-resources/", "manage/"}) {
            assertEquals(403, wc.goTo(url, null).getWebResponse().getStatusCode(), url);
        }
    }
}
