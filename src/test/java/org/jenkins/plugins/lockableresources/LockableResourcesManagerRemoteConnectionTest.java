/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockableResourcesManagerRemoteConnectionTest {

    private LockableResourcesManager manager;
    private JenkinsRule rule;

    @BeforeEach
    void setUp(JenkinsRule r) {
        rule = r;
        manager = Jenkins.get().getDescriptorByType(LockableResourcesManager.class);
        assertNotNull(manager);
    }

    @Test
    void testRemotesListIsInitialized() {
        List<RemoteConnection> remotes = manager.getRemotes();

        assertNotNull(remotes);
    }

    @Test
    void testSetAndGetRemotes() {
        List<RemoteConnection> remotes = new ArrayList<>();
        remotes.add(new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1"));
        remotes.add(new RemoteConnection("server2", "http://jenkins2.example.com", "creds-2"));

        manager.setRemotes(remotes);

        List<RemoteConnection> retrieved = manager.getRemotes();
        assertEquals(2, retrieved.size());
        assertEquals("server1", retrieved.get(0).getServerId());
        assertEquals("http://jenkins1.example.com", retrieved.get(0).getUrl());
    }

    @Test
    void testGetRemotesReturnsCopy() {
        List<RemoteConnection> remotes = new ArrayList<>();
        remotes.add(new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1"));
        manager.setRemotes(remotes);

        List<RemoteConnection> returned = manager.getRemotes();
        returned.add(new RemoteConnection("server2", "http://jenkins2.example.com", "creds-2"));

        assertEquals(1, manager.getRemotes().size());
    }

    @Test
    void testGetRemotesAsMap() {
        List<RemoteConnection> remotes = new ArrayList<>();
        remotes.add(new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1"));
        remotes.add(new RemoteConnection("server2", "http://jenkins2.example.com", "creds-2"));
        manager.setRemotes(remotes);

        Map<String, RemoteConnection> remotesMap = manager.getRemotesAsMap();

        assertEquals(2, remotesMap.size());
        assertTrue(remotesMap.containsKey("server1"));
        assertTrue(remotesMap.containsKey("server2"));
        assertEquals("http://jenkins1.example.com", remotesMap.get("server1").getUrl());
    }

    @Test
    void testGetRemotesAsMapIsUnmodifiable() {
        List<RemoteConnection> remotes = new ArrayList<>();
        remotes.add(new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1"));
        manager.setRemotes(remotes);

        Map<String, RemoteConnection> remotesMap = manager.getRemotesAsMap();

        assertThrows(UnsupportedOperationException.class, () -> remotesMap.put("server2", null));
    }

    @Test
    void testGetRemotesAsMapUsesLastEntryForDuplicateServerId() {
        List<RemoteConnection> remotes = new ArrayList<>();
        remotes.add(new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1"));
        remotes.add(new RemoteConnection("server1", "http://jenkins1-updated.example.com", "creds-1b"));
        manager.setRemotes(remotes);

        Map<String, RemoteConnection> remotesMap = manager.getRemotesAsMap();

        assertEquals(1, remotesMap.size());
        assertEquals(
                "http://jenkins1-updated.example.com", remotesMap.get("server1").getUrl());
    }

    @Test
    void testSetRemotesRejectsInvalidRemote() {
        List<RemoteConnection> remotes = new ArrayList<>();
        remotes.add(new RemoteConnection("", "http://jenkins1.example.com", "creds-1"));

        assertThrows(IllegalArgumentException.class, () -> manager.setRemotes(remotes));
    }

    @Test
    void testSetRemotesRejectsNullEntry() {
        List<RemoteConnection> remotes = new ArrayList<>();
        remotes.add(null);

        assertThrows(IllegalArgumentException.class, () -> manager.setRemotes(remotes));
    }

    @Test
    void testRemotesPersistenceAcrossReload() throws Exception {
        List<RemoteConnection> remotes = new ArrayList<>();
        remotes.add(new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1"));
        manager.setRemotes(remotes);

        rule.jenkins.reload();
        LockableResourcesManager reloaded = Jenkins.get().getDescriptorByType(LockableResourcesManager.class);
        assertNotNull(reloaded);

        List<RemoteConnection> reloadedRemotes = reloaded.getRemotes();

        assertEquals(1, reloadedRemotes.size());
        assertEquals("server1", reloadedRemotes.get(0).getServerId());
        assertEquals("http://jenkins1.example.com", reloadedRemotes.get(0).getUrl());
        assertEquals("creds-1", reloadedRemotes.get(0).getCredentialsId());
    }

    @Test
    void testForcedServerIdSetterAndGetter() {
        manager.setForcedServerId("server-x");
        assertEquals("server-x", manager.getForcedServerId());

        manager.setForcedServerId(null);
        assertEquals("", manager.getForcedServerId());

        manager.setForcedServerId("  trimmed  ");
        assertEquals("trimmed", manager.getForcedServerId());
    }

    @Test
    void testForcedServerIdPersistenceAcrossReload() throws Exception {
        manager.setRemotes(List.of(new RemoteConnection("server-a", "http://jenkins-a.example.com", "creds-a")));
        manager.setForcedServerId("server-a");
        manager.save();

        rule.jenkins.reload();
        LockableResourcesManager reloaded = Jenkins.get().getDescriptorByType(LockableResourcesManager.class);
        assertNotNull(reloaded);

        assertEquals("server-a", reloaded.getForcedServerId());
    }

    @Test
    void testGlobalConfigSubmitRoundTripForRemoteSettings() throws Exception {
        manager.setRemoteApiEnabled(false);
        manager.setExposeLabel("old-label");
        manager.setRemotes(List.of(new RemoteConnection("server-a", "http://jenkins-a.example.com", "creds-a")));

        HtmlPage page = rule.createWebClient().goTo("configure");
        HtmlForm form = page.getFormByName("config");

        HtmlCheckBoxInput remoteApiEnabled = form.getInputByName("_.remoteApiEnabled");
        remoteApiEnabled.setChecked(true);

        HtmlTextInput exposeLabel = form.getInputByName("_.exposeLabel");
        exposeLabel.setValueAttribute("remote-only");

        rule.submit(form);

        assertTrue(manager.isRemoteApiEnabled());
        assertEquals("remote-only", manager.getExposeLabel());

        List<RemoteConnection> savedRemotes = manager.getRemotes();
        assertEquals(1, savedRemotes.size());
        assertEquals("server-a", savedRemotes.get(0).getServerId());
        assertEquals("http://jenkins-a.example.com", savedRemotes.get(0).getUrl());
        assertEquals("creds-a", savedRemotes.get(0).getCredentialsId());
    }

    @Test
    void testDoCheckForcedServerIdOkWhenEmpty() {
        assertEquals(hudson.util.FormValidation.Kind.OK, manager.doCheckForcedServerId("").kind);
        assertEquals(hudson.util.FormValidation.Kind.OK, manager.doCheckForcedServerId(null).kind);
        assertEquals(hudson.util.FormValidation.Kind.OK, manager.doCheckForcedServerId("  ").kind);
    }

    @Test
    void testDoCheckForcedServerIdWarnsWhenNotConfigured() {
        manager.setRemotes(List.of(new RemoteConnection("server-a", "http://jenkins-a.example.com", "")));

        assertEquals(hudson.util.FormValidation.Kind.WARNING, manager.doCheckForcedServerId("server-b").kind);
    }

    @Test
    void testDoCheckForcedServerIdOkWhenConfigured() {
        manager.setRemotes(List.of(new RemoteConnection("server-a", "http://jenkins-a.example.com", "")));

        assertEquals(hudson.util.FormValidation.Kind.OK, manager.doCheckForcedServerId("server-a").kind);
        // Leading/trailing whitespace is trimmed before matching
        assertEquals(hudson.util.FormValidation.Kind.OK, manager.doCheckForcedServerId(" server-a ").kind);
    }
}
