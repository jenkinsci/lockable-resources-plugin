package org.jenkins.plugins.lockableresources.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.AccessDeniedException3;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.LockStepTestBase;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

@WithJenkins
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LockableResourcesRootActionTest extends LockStepTestBase {

    private JenkinsRule j;

    @Mock
    private StaplerRequest2 req;

    @Mock
    private StaplerResponse2 rsp;

    private final String USER = "user";
    private User user;
    private final String USER_WITH_CONFIGURE_PERM = "cfg_user";
    private User cfg_user;
    private final String USER_WITH_RESERVE_PERM = "reserve_user1";
    private User reserve_user1;
    private final String USER_WITH_RESERVE_PERM_2 = "reserve_user2";
    private User reserve_user2;
    private final String USER_WITH_STEAL_PERM = "steal_user";
    private User steal_user;
    private final String USER_WITH_UNLOCK_PERM = "unlock_user";
    private User unlock_user;
    private final String ADMIN = "admin";
    private User admin;

    private LockableResourcesManager LRM = null;

    // ---------------------------------------------------------------------------
    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;

        this.user = User.getById(this.USER, true);
        this.cfg_user = User.getById(this.USER_WITH_CONFIGURE_PERM, true);
        this.reserve_user1 = User.getById(this.USER_WITH_RESERVE_PERM, true);
        this.reserve_user2 = User.getById(this.USER_WITH_RESERVE_PERM_2, true);
        this.steal_user = User.getById(this.USER_WITH_STEAL_PERM, true);
        this.unlock_user = User.getById(this.USER_WITH_UNLOCK_PERM, true);
        this.admin = User.getById(this.ADMIN, true);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        this.j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to(this.USER)
                .grant(Jenkins.READ, Item.CONFIGURE)
                .everywhere()
                .to(this.USER_WITH_CONFIGURE_PERM)
                .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.RESERVE)
                .everywhere()
                .to(this.USER_WITH_RESERVE_PERM)
                .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.RESERVE)
                .everywhere()
                .to(this.USER_WITH_RESERVE_PERM_2)
                .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.STEAL)
                .everywhere()
                .to(this.USER_WITH_STEAL_PERM)
                .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.UNLOCK)
                .everywhere()
                .to(this.USER_WITH_UNLOCK_PERM)
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to(this.ADMIN));

        this.LRM = LockableResourcesManager.get();
    }

    // ---------------------------------------------------------------------------
    /**
     * Test action doReassign in web client. The action shall do: ``` Reserves a resource that may be
     * or not be reserved by some person already, giving it away to the userName indefinitely (until
     * that person, or some explicit scripted action, decides to release the resource). ```
     *
     * @throws Exception
     */
    @Test
    void testDoReassign() throws Exception {
        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();
        LockableResource resource = this.createResource("resource1");

        // somebody
        SecurityContextHolder.getContext().setAuthentication(this.user.impersonate2());
        assertThrows(AccessDeniedException.class, () -> action.doReserve(req, rsp));
        assertFalse(resource.isReserved(), "user without permission");

        // switch to suer with reserve permission
        SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());

        assertFalse(resource.isReserved(), "is free");
        action.doReserve(req, rsp);
        assertEquals(this.reserve_user1.getId(), resource.getReservedBy(), "reserved by user");

        // try to reassign as other user
        // it shall not changes, because the second user has not steal permission
        SecurityContextHolder.getContext().setAuthentication(this.reserve_user2.impersonate2());
        assertThrows(AccessDeniedException.class, () -> action.doReassign(req, rsp));
        assertEquals(this.reserve_user1.getId(), resource.getReservedBy(), "still reserved by user");

        // switch to user who has reserved the resource and try to reassign
        // The user can no do any action here, because he has no STEAL permission
        SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
        assertThrows(AccessDeniedException.class, () -> action.doReassign(req, rsp));
        assertEquals(this.reserve_user1.getId(), resource.getReservedBy(), "reserved by user");

        // switch to admin and try to reassign
        SecurityContextHolder.getContext().setAuthentication(this.admin.impersonate2());
        action.doReassign(req, rsp);
        assertEquals(this.admin.getId(), resource.getReservedBy(), "reserved by admin");

        // try to steal reservation
        SecurityContextHolder.getContext().setAuthentication(this.steal_user.impersonate2());
        action.doReassign(req, rsp);
        assertEquals(this.steal_user.getId(), resource.getReservedBy(), "reserved by steal user");

        // do reassign your self, makes no sense, but the application shall not crashed
        action.doReassign(req, rsp);
        assertEquals(this.steal_user.getId(), resource.getReservedBy(), "reserved by steal user");

        // defensive tests
        when(req.getParameter("resource")).thenReturn("this-one-does-not-exists");
        action.doReassign(req, rsp);
    }

    // ---------------------------------------------------------------------------
    @Test
    void testDoReserve() throws Exception {

        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();
        LockableResource resource;

        // nobody (system user)
        // system must be permitted to create resource
        resource = this.createResource("resource1");
        action.doReserve(req, rsp);
        assertTrue(resource.isReserved(), "is reserved by system");

        // somebody
        SecurityContextHolder.getContext().setAuthentication(this.user.impersonate2());
        resource = this.createResource("resource2");
        assertThrows(AccessDeniedException3.class, () -> action.doReserve(req, rsp));
        assertFalse(resource.isReserved(), "user without permission");

        // first user. This shall works
        SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
        action.doReserve(req, rsp);
        assertTrue(resource.isReserved(), "reserved by first user");

        // second user, shall not work, because it is reserved just now
        SecurityContextHolder.getContext().setAuthentication(this.reserve_user2.impersonate2());
        action.doReserve(req, rsp);
        assertEquals(this.reserve_user1.getId(), resource.getReservedBy(), "still reserved by first user");

        // but create new one and reserve it must works as well
        resource = this.createResource("resource3");
        action.doReserve(req, rsp);
        assertEquals(this.reserve_user2.getId(), resource.getReservedBy(), "reserved by second user");

        // and also admin can not reserve resource, when is reserved just now (need to use reassign
        // action)
        SecurityContextHolder.getContext().setAuthentication(this.admin.impersonate2());
        action.doReserve(req, rsp);
        assertEquals(this.reserve_user2.getId(), resource.getReservedBy(), "still reserved by second user");

        // try to reserve by label name
        resource = this.createResource("resource4");
        when(req.getParameter("resource")).thenReturn(resource.getLabels());
        action.doReserve(req, rsp);
        // this is not supported at the moment, therefore expected == null
        assertNull(resource.getReservedBy(), "check by label name :" + resource.getLabels());

        // invalid params. Just check if it crash here
        when(req.getParameter("resource")).thenReturn("this-one-does-not-exists");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("this one does not exists");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn(null);
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("some-dangerous_characters-like:\n\t$%ÖÜä?=+ľšť");
        action.doReserve(req, rsp);
    }

    // ---------------------------------------------------------------------------
    @Test
    void testDoReset() throws IOException, ServletException {

        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();
        LockableResource resource;

        // nobody (system user)
        // system must be permitted to create resource
        resource = this.createResource("resource1");
        action.doReserve(req, rsp);
        assertTrue(resource.isReserved(), "is reserved by system");
        action.doReset(req, rsp);
        assertFalse(resource.isReserved(), "is reset by system ");

        // somebody
        SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
        action.doReserve(req, rsp);
        assertTrue(resource.isReserved(), "is reserved by user1 ");
        assertThrows(AccessDeniedException3.class, () -> action.doReset(req, rsp));
        assertTrue(resource.isReserved(), "still reserved by user1");

        // switch to user with unlock permission
        SecurityContextHolder.getContext().setAuthentication(this.unlock_user.impersonate2());
        action.doReset(req, rsp);
        assertFalse(resource.isReserved(), "unreserved");

        // invalid params. Just check if it crash here
        when(req.getParameter("resource")).thenReturn("this-one-does-not-exists");
        action.doReset(req, rsp);
        when(req.getParameter("resource")).thenReturn("this one does not exists");
        action.doReset(req, rsp);
        when(req.getParameter("resource")).thenReturn(null);
        action.doReset(req, rsp);
        when(req.getParameter("resource")).thenReturn("");
        action.doReset(req, rsp);
        when(req.getParameter("resource")).thenReturn("some-dangerous_characters-like:\n\t$%ÖÜä?=+ľšť");
        action.doReset(req, rsp);
    }

    // ---------------------------------------------------------------------------
    @Test
    void testDoSaveNote() throws IOException, ServletException {

        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();
        LockableResource resource;

        // nobody (system user)
        // system must be permitted to create resource
        resource = this.createResource("resource1");
        assertEquals("", resource.getNote(), "default note");
        when(req.getParameter("note")).thenReturn("this is my note");
        action.doSaveNote(req, rsp);
        assertEquals("this is my note", resource.getNote(), "default note");

        // somebody
        SecurityContextHolder.getContext().setAuthentication(this.user.impersonate2());
        assertThrows(AccessDeniedException3.class, () -> action.doSaveNote(req, rsp));
        assertEquals("this is my note", resource.getNote(), "default note");

        // switch to user with reserve permission
        SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
        when(req.getParameter("note")).thenReturn("this is my note from user1");
        action.doSaveNote(req, rsp);
        assertEquals("this is my note from user1", resource.getNote(), "default note");

        // switch to other user with reserve permission
        SecurityContextHolder.getContext().setAuthentication(this.reserve_user2.impersonate2());
        when(req.getParameter("note")).thenReturn("this is my note from user2");
        action.doSaveNote(req, rsp);
        assertEquals("this is my note from user2", resource.getNote(), "default note");

        // invalid params. Just check if it crash here
        SecurityContextHolder.getContext().setAuthentication(this.admin.impersonate2());
        when(req.getParameter("note")).thenReturn("");
        action.doSaveNote(req, rsp);
        when(req.getParameter("note")).thenReturn(null);
        action.doSaveNote(req, rsp);

        when(req.getParameter("resource")).thenReturn("this-one-does-not-exists");
        action.doSaveNote(req, rsp);
        when(req.getParameter("resource")).thenReturn("this one does not exists");
        action.doSaveNote(req, rsp);
        when(req.getParameter("resource")).thenReturn(null);
        action.doSaveNote(req, rsp);
        when(req.getParameter("resource")).thenReturn("");
        action.doSaveNote(req, rsp);
        when(req.getParameter("resource")).thenReturn("some-dangerous_characters-like:\n\t$%ÖÜä?=+ľšť");
        action.doSaveNote(req, rsp);
    }

    // ---------------------------------------------------------------------------
    @Test
    void testDoSteal() throws IOException, ServletException {

        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();
        LockableResource resource;

        // nobody (system user)
        // system must be permitted to create resource
        resource = this.createResource("resource1");
        // when the resource is not reserved, the doSteal action reserve it for you
        assertNull(resource.getReservedTimestamp());
        action.doSteal(req, rsp);
        assertTrue(resource.isReserved(), "is reserved by system");
        assertNotNull(resource.getReservedTimestamp());

        // somebody
        SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
        action.doReserve(req, rsp);
        assertTrue(resource.isReserved(), "is reserved by user1 ");
        assertThrows(AccessDeniedException3.class, () -> action.doSteal(req, rsp));
        assertTrue(resource.isReserved(), "still reserved by user1");

        // switch as admin and reset
        SecurityContextHolder.getContext().setAuthentication(this.admin.impersonate2());
        action.doReset(req, rsp);
        assertFalse(resource.isReserved(), "unreserved");
        assertNull(resource.getReservedTimestamp());

        // switch to user1 and reserve it
        action.doReserve(req, rsp);
        assertTrue(resource.isReserved(), "is reserved by user1");
        assertNotNull(resource.getReservedTimestamp());

        // switch to user with STEAL permission
        SecurityContextHolder.getContext().setAuthentication(this.steal_user.impersonate2());
        action.doSteal(req, rsp);
        assertEquals(this.steal_user.getId(), resource.getReservedBy(), "reserved by user");
        assertNotNull(resource.getReservedTimestamp());

        // invalid params. Just check if it crash here
        SecurityContextHolder.getContext().setAuthentication(this.admin.impersonate2());
        when(req.getParameter("resource")).thenReturn("this-one-does-not-exists");
        action.doSteal(req, rsp);
        when(req.getParameter("resource")).thenReturn("this one does not exists");
        action.doSteal(req, rsp);
        when(req.getParameter("resource")).thenReturn(null);
        action.doSteal(req, rsp);
        when(req.getParameter("resource")).thenReturn("");
        action.doSteal(req, rsp);
        when(req.getParameter("resource")).thenReturn("some-dangerous_characters-like:\n\t$%ÖÜä?=+ľšť");
        action.doSteal(req, rsp);
    }

    // ---------------------------------------------------------------------------
    @Test
    void testDoUnlock() {}

    // ---------------------------------------------------------------------------
    @Test
    void testDoUnreserve() {}

    // ---------------------------------------------------------------------------
    @Test
    void testDoChangeQueueOrder() throws Exception {

        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();
        LockableResource resource = this.createResource("resource1");

        assertEquals(0, action.getQueue().getAll().size(), "initial queue size");

        // start few jobs to simulate queue
        action.doReserve(req, rsp);
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
            lock('resource1') {
                echo('I am inside')
            }
            """,
                true));

        WorkflowRun r1 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: resource1] is not free, waiting for execution ...", r1);
        WorkflowRun r2 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: resource1] is not free, waiting for execution ...", r2);
        WorkflowRun r3 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: resource1] is not free, waiting for execution ...", r3);

        List<LockableResourcesRootAction.Queue.QueueStruct> queueItems =
                action.getQueue().getAll();
        assertEquals(3, queueItems.size(), "queue size");

        // nobody (system user)
        // system must be permitted to create resource
        // when the resource is not reserved, the doSteal action reserve it for you
        String id = queueItems.get(0).getId();
        when(req.getParameter("id")).thenReturn(id);
        when(req.getParameter("index")).thenReturn("2");
        action.doChangeQueueOrder(req, rsp);
        queueItems = action.getQueue().getAll();
        assertEquals(id, queueItems.get(1).getId(), "is reserved by system");

        // invalid params. Just check if it crash here
        when(req.getParameter("id")).thenReturn("in-valid-ID");
        when(req.getParameter("index")).thenReturn("2");
        action.doChangeQueueOrder(req, rsp);

        when(req.getParameter("id")).thenReturn(id);
        when(req.getParameter("index")).thenReturn("in-valid-position");
        action.doChangeQueueOrder(req, rsp);

        when(req.getParameter("id")).thenReturn(id);
        when(req.getParameter("index")).thenReturn("0");
        action.doChangeQueueOrder(req, rsp);

        when(req.getParameter("id")).thenReturn(id);
        when(req.getParameter("index")).thenReturn("4");
        action.doChangeQueueOrder(req, rsp);
    }

    // ---------------------------------------------------------------------------
    @Test
    void testGetAllLabels() {
        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();

        this.LRM.createResourceWithLabel("resource-A", "resource-label-1 ");
        this.LRM.createResourceWithLabel("resource-B", "resource-label-1 resource-label-2");
        this.LRM.createResourceWithLabel(" resource-C", "resource-label-1 \n \t \r resource-label-2 resource-label-3");
        this.LRM.createResourceWithLabel("resource-D", null);

        Set<String> expectedLabels = new HashSet<>();
        expectedLabels.add("resource-label-1");
        expectedLabels.add("resource-label-2");
        expectedLabels.add("resource-label-3");

        Set<String> labels = action.getAllLabels();
        assertEquals(expectedLabels, labels, "check all labels");

        LockableResource getter = action.getResource("resource-C");
        assertEquals(
                "resource-label-1 resource-label-2 resource-label-3",
                getter.getLabels(),
                "check labels from resource-C");
        assertEquals("resource-C", getter.getName(), "check resource name");
    }

    // ---------------------------------------------------------------------------
    @Test
    void testGetAssignedResourceAmount() throws IOException, ServletException {
        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();

        // initial check, labels are not used
        assertEquals(0, action.getAssignedResourceAmount("resource-A-label"), "initial check");

        this.LRM.createResourceWithLabel("resource-A", "resource-label-1");
        this.LRM.createResourceWithLabel("resource-B", "resource-label-1 resource-label-2");
        this.LRM.createResourceWithLabel("resource-C", "resource-label-1 resource-label-2 resource-label-3");

        assertEquals(3, action.getAssignedResourceAmount("resource-label-1"), "initial check");
        assertEquals(2, action.getAssignedResourceAmount("resource-label-2"), "initial check");
        assertEquals(1, action.getAssignedResourceAmount("resource-label-3"), "initial check");

        // check label parsing
        assertEquals(3, action.getAssignedResourceAmount("resource-label-1"), "check after reservation");
        assertEquals(
                1,
                action.getAssignedResourceAmount("resource-label-1 && resource-label-2 && resource-label-3"),
                "check after reservation");
        assertEquals(
                2, action.getAssignedResourceAmount("resource-label-1 && resource-label-2"), "check after reservation");
        assertEquals(
                2, action.getAssignedResourceAmount("resource-label-3 || resource-label-2"), "check after reservation");

        // reserve one resource. Amount of assigned labels should change
        when(req.getParameter("resource")).thenReturn("resource-A");
        action.doReserve(req, rsp);

        assertEquals(3, action.getAssignedResourceAmount("resource-label-1"), "check after reservation");
        assertEquals(2, action.getAssignedResourceAmount("resource-label-2"), "check after reservation");
        assertEquals(1, action.getAssignedResourceAmount("resource-label-3"), "check after reservation");

        // reserve all resources. Amount of assigned labels should change
        when(req.getParameter("resource")).thenReturn("resource-B");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("resource-C");
        action.doReserve(req, rsp);

        assertEquals(3, action.getAssignedResourceAmount("resource-label-1"), "check after reservation");
        assertEquals(2, action.getAssignedResourceAmount("resource-label-2"), "check after reservation");
        assertEquals(1, action.getAssignedResourceAmount("resource-label-3"), "check after reservation");

        // defensive tests
        assertEquals(0, action.getAssignedResourceAmount(""), "initial check");
        assertEquals(0, action.getAssignedResourceAmount(null), "initial check");
        assertEquals(0, action.getAssignedResourceAmount("resource-A-label "), "initial check");
    }

    // ---------------------------------------------------------------------------
    @Test
    void testGetFreeResourceAmount() throws IOException, ServletException {
        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();

        // initial check, labels are not used
        assertEquals(0, action.getFreeResourceAmount("resource-A-label"), "initial check");

        this.LRM.createResourceWithLabel("resource-A", "resource-label-1");
        this.LRM.createResourceWithLabel("resource-B", "resource-label-1 resource-label-2");
        this.LRM.createResourceWithLabel("resource-C", "resource-label-1 resource-label-2 resource-label-3");

        assertEquals(3, action.getFreeResourceAmount("resource-label-1"), "initial check");
        assertEquals(2, action.getFreeResourceAmount("resource-label-2"), "initial check");
        assertEquals(1, action.getFreeResourceAmount("resource-label-3"), "initial check");

        when(req.getParameter("resource")).thenReturn("resource-A");
        action.doReserve(req, rsp);

        assertEquals(2, action.getFreeResourceAmount("resource-label-1"), "check after label-1 is reserved");
        assertEquals(2, action.getFreeResourceAmount("resource-label-2"), "check after label-1 is reserved");
        assertEquals(1, action.getFreeResourceAmount("resource-label-3"), "check after label-1 is reserved");

        when(req.getParameter("resource")).thenReturn("resource-B");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("resource-C");
        action.doReserve(req, rsp);

        assertEquals(0, action.getFreeResourceAmount("resource-label-1"), "check after label-2 is reserved");
        assertEquals(0, action.getFreeResourceAmount("resource-label-2"), "check after label-2 is reserved");
        assertEquals(0, action.getFreeResourceAmount("resource-label-3"), "check after label-2 is reserved");

        // defensive tests
        assertEquals(0, action.getFreeResourceAmount(""), "defensive check");
        assertEquals(0, action.getFreeResourceAmount(null), "defensive check");
        assertEquals(0, action.getFreeResourceAmount("resource-A-label "), "defensive check");
    }

    // ---------------------------------------------------------------------------
    @Test
    void testGetFreeResourcePercentage() throws IOException, ServletException {
        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();

        // initial check, labels are not used
        assertEquals(0, action.getFreeResourcePercentage("resource-A-label"), "initial check");

        this.LRM.createResourceWithLabel("resource-A", "resource-label-1");
        this.LRM.createResourceWithLabel("resource-B", "resource-label-1 resource-label-2");
        this.LRM.createResourceWithLabel("resource-C", "resource-label-1 resource-label-2 resource-label-3");
        this.LRM.createResourceWithLabel("resource-D", "resource-label-1 resource-label-2 resource-label-3");
        this.LRM.createResourceWithLabel("resource-E", "resource-label-1 resource-label-2 resource-label-3");
        this.LRM.createResourceWithLabel("resource-F", "resource-label-1 resource-label-2 resource-label-3");
        this.LRM.createResourceWithLabel("resource-G", "resource-label-1 resource-label-2 resource-label-3");
        this.LRM.createResourceWithLabel("resource-H", "resource-label-1 resource-label-2 resource-label-3");
        this.LRM.createResourceWithLabel("resource-I", "resource-label-1 resource-label-2 resource-label-3");
        this.LRM.createResourceWithLabel("resource-J", "resource-label-1 resource-label-2 resource-label-3");

        assertEquals(100, action.getFreeResourcePercentage("resource-label-1"), "initial check");
        assertEquals(100, action.getFreeResourcePercentage("resource-label-2"), "initial check");
        assertEquals(100, action.getFreeResourcePercentage("resource-label-3"), "initial check");

        // reserve one resource. Amount of assigned labels should change
        when(req.getParameter("resource")).thenReturn("resource-A");
        action.doReserve(req, rsp);

        assertEquals(90, action.getFreeResourcePercentage("resource-label-1"), "check after reservation");
        assertEquals(100, action.getFreeResourcePercentage("resource-label-2"), "check after reservation");

        when(req.getParameter("resource")).thenReturn("resource-B");
        action.doReserve(req, rsp);
        assertEquals(80, action.getFreeResourcePercentage("resource-label-1"), "check after reservation");
        assertEquals(88, action.getFreeResourcePercentage("resource-label-2"), "check after reservation");

        // reserve all resources. Amount of assigned labels should change
        when(req.getParameter("resource")).thenReturn("resource-C");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("resource-D");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("resource-E");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("resource-F");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("resource-G");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("resource-H");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("resource-I");
        action.doReserve(req, rsp);
        when(req.getParameter("resource")).thenReturn("resource-J");
        action.doReserve(req, rsp);

        assertEquals(0, action.getFreeResourcePercentage("resource-label-1"), "check after all reserved");
        assertEquals(0, action.getFreeResourcePercentage("resource-label-2"), "check after all reserved");
        assertEquals(0, action.getFreeResourcePercentage("resource-label-3"), "check after all reserved");

        // defensive tests
        assertEquals(0, action.getFreeResourcePercentage(""), "defensive check");
        assertEquals(0, action.getFreeResourcePercentage(null), "defensive check");
        assertEquals(0, action.getFreeResourcePercentage("resource-A-label "), "defensive check");
    }

    // ---------------------------------------------------------------------------
    @Test
    void testGetIconFileName() {}

    // ---------------------------------------------------------------------------
    @Test
    void testGetNumberOfAllLabels() {
        when(req.getMethod()).thenReturn("POST");
        LockableResourcesRootAction action = new LockableResourcesRootAction();

        // initial check, labels are not used
        assertEquals(0, action.getNumberOfAllLabels(), "initial check");

        this.LRM.createResourceWithLabel("resource-A", "resource-label-1");
        assertEquals(1, action.getNumberOfAllLabels(), "one resource with one label");
        this.LRM.createResourceWithLabel("resource-B", "resource-label-1 resource-label-2");
        assertEquals(2, action.getNumberOfAllLabels(), "two resources with 2 labels");
        this.LRM.createResourceWithLabel("resource-C", "resource-label-1 resource-label-2 resource-label-3");
        assertEquals(3, action.getNumberOfAllLabels(), "three resources with three labels");
        this.LRM.createResourceWithLabel("resource-D", "resource-label-1 resource-label-2 resource-label-3");
        assertEquals(3, action.getNumberOfAllLabels(), "four resources with three labels");
    }

    // ---------------------------------------------------------------------------
    @Test
    void testGetUrlName() {}

    // ---------------------------------------------------------------------------
    @Test
    void testGetUserName() {}

    // ---------------------------------------------------------------------------
    private LockableResource createResource(String resourceName) {
        this.LRM.createResourceWithLabel(resourceName, "label-" + resourceName);
        when(req.getParameter("resource")).thenReturn(resourceName);
        return LRM.fromName(resourceName);
    }
}
