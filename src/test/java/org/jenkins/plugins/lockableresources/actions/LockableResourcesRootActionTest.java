package org.jenkins.plugins.lockableresources.actions;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;


import hudson.model.Item;
import hudson.model.User;
import hudson.security.AccessDeniedException3;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.LockStepTestBase;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;


public class LockableResourcesRootActionTest extends LockStepTestBase {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Mock
  private StaplerRequest req;

  @Mock
  private StaplerResponse rsp;

  private AutoCloseable mocks;

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

  //---------------------------------------------------------------------------
  @Before
  public void setUp() throws Exception {
    this.mocks = MockitoAnnotations.openMocks(this);

    this.user = User.getById(this.USER, true);
    this.cfg_user = User.getById(this.USER_WITH_CONFIGURE_PERM, true);
    this.reserve_user1 = User.getById(this.USER_WITH_RESERVE_PERM, true);
    this.reserve_user2 = User.getById(this.USER_WITH_RESERVE_PERM_2, true);
    this.steal_user = User.getById(this.USER_WITH_STEAL_PERM, true);
    this.unlock_user = User.getById(this.USER_WITH_UNLOCK_PERM, true);
    this.admin = User.getById(this.ADMIN, true);

    j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
    this.j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
      .grant(Jenkins.READ).everywhere().to(this.USER)
      .grant(Jenkins.READ, Item.CONFIGURE).everywhere().to(this.USER_WITH_CONFIGURE_PERM)
      .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.RESERVE).everywhere().to(this.USER_WITH_RESERVE_PERM)
      .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.RESERVE).everywhere().to(this.USER_WITH_RESERVE_PERM_2)
      .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.STEAL).everywhere().to(this.USER_WITH_STEAL_PERM)
      .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.UNLOCK).everywhere().to(this.USER_WITH_UNLOCK_PERM)
      .grant(Jenkins.ADMINISTER).everywhere().to(this.ADMIN)
    );

    this.LRM = LockableResourcesManager.get();
  }

  @After
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  //---------------------------------------------------------------------------
  /** Test action doReassign  in web client.
   * The action shall do:
   * ```
   * Reserves a resource that may be or not be reserved by some person already, giving it away to
   * the userName indefinitely (until that person, or some explicit scripted action, decides to
   * release the resource).
   * ```
 * @throws Exception
   */
  @Test
  public void testDoReassign() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();
    LockableResource resource = this.createResource("resource1");

    // somebody
    SecurityContextHolder.getContext().setAuthentication(this.user.impersonate2());
    assertThrows(AccessDeniedException.class, () -> action.doReserve(req, rsp));
    assertFalse("user without permission", resource.isReserved());

    // switch to suer with reserve permission
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());

    assertFalse("is free", resource.isReserved());
    action.doReserve(req, rsp);
    assertEquals("reserved by user", this.reserve_user1.getId(), resource.getReservedBy());

    // try to reassign as other user
    // it shall not changes, because the second user has not steal permission
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user2.impersonate2());
    assertThrows(AccessDeniedException.class, () -> action.doReassign(req, rsp));
    assertEquals("still reserved by user", this.reserve_user1.getId(), resource.getReservedBy());

    // switch to user who has reserved the resource and try to reassign
    // The user can no do any action here, because he has no STEAL permission
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
    assertThrows(AccessDeniedException.class, () -> action.doReassign(req, rsp));
    assertEquals("reserved by user", this.reserve_user1.getId(), resource.getReservedBy());

    // switch to admin and try to reassign
    SecurityContextHolder.getContext().setAuthentication(this.admin.impersonate2());
    action.doReassign(req, rsp);
    assertEquals("reserved by admin", this.admin.getId(), resource.getReservedBy());

    // try to steal reservation
    SecurityContextHolder.getContext().setAuthentication(this.steal_user.impersonate2());
    action.doReassign(req, rsp);
    assertEquals("reserved by steal user", this.steal_user.getId(), resource.getReservedBy());

    // do reassign your self, makes no sense, but the application shall not crashed
    action.doReassign(req, rsp);
    assertEquals("reserved by steal user", this.steal_user.getId(), resource.getReservedBy());

    // defensive tests
    when(req.getParameter("resource")).thenReturn("this-one-does-not-exists");
    action.doReassign(req, rsp);
  }

  //---------------------------------------------------------------------------
  @Test
  public void testDoReserve() throws Exception {

    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();
    LockableResource resource = null;

    // nobody (system user)
    // system must be permitted to create resource
    resource = this.createResource("resource1");
    action.doReserve(req, rsp);
    assertTrue("is reserved by system", resource.isReserved());

    // somebody
    SecurityContextHolder.getContext().setAuthentication(this.user.impersonate2());
    resource = this.createResource("resource2");
    assertThrows(AccessDeniedException3.class, () -> action.doReserve(req, rsp));
    assertFalse("user without permission", resource.isReserved());

    // first user. This shall works
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
    action.doReserve(req, rsp);
    assertTrue("reserved by first user", resource.isReserved());

    // second user, shall not work, because it is reserved just now
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user2.impersonate2());
    action.doReserve(req, rsp);
    assertEquals("still reserved by first user", this.reserve_user1.getId(), resource.getReservedBy());

    // but create new one and reserve it must works as well
    resource = this.createResource("resource3");
    action.doReserve(req, rsp);
    assertEquals("reserved by second user", this.reserve_user2.getId(), resource.getReservedBy());

    // and also admin can not reserve resource, when is reserved just now (need to use reassign action)
    SecurityContextHolder.getContext().setAuthentication(this.admin.impersonate2());
    action.doReserve(req, rsp);
    assertEquals("still reserved by second user", this.reserve_user2.getId(), resource.getReservedBy());

    // try to reserve by label name
    resource = this.createResource("resource4");
    when(req.getParameter("resource")).thenReturn(resource.getLabels());
    action.doReserve(req, rsp);
    // this is not supported at the moment, therefore expected == null
    assertEquals("check by label name :" + resource.getLabels(), null, resource.getReservedBy());

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

  //---------------------------------------------------------------------------
  @Test
  public void testDoReset() throws IOException, ServletException {

    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();
    LockableResource resource = null;

    // nobody (system user)
    // system must be permitted to create resource
    resource = this.createResource("resource1");
    action.doReserve(req, rsp);
    assertTrue("is reserved by system", resource.isReserved());
    action.doReset(req, rsp);
    assertFalse("is reset by system ", resource.isReserved());

    // somebody
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
    action.doReserve(req, rsp);
    assertTrue("is reserved by user1 ", resource.isReserved());
    assertThrows(AccessDeniedException3.class, () -> action.doReset(req, rsp));
    assertTrue("still reserved by user1", resource.isReserved());

    // switch to user with unlock permission
    SecurityContextHolder.getContext().setAuthentication(this.unlock_user.impersonate2());
    action.doReset(req, rsp);
    assertFalse("unreserved", resource.isReserved());

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

  //---------------------------------------------------------------------------
  @Test
  public void testDoSaveNote() throws IOException, ServletException {

    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();
    LockableResource resource = null;

    // nobody (system user)
    // system must be permitted to create resource
    resource = this.createResource("resource1");
    assertEquals("default note", "", resource.getNote());
    when(req.getParameter("note")).thenReturn("this is my note");
    action.doSaveNote(req, rsp);
    assertEquals("default note", "this is my note", resource.getNote());

    // somebody
    SecurityContextHolder.getContext().setAuthentication(this.user.impersonate2());
    assertThrows(AccessDeniedException3.class, () -> action.doSaveNote(req, rsp));
    assertEquals("default note", "this is my note", resource.getNote());

    // switch to user with reserve permission
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
    when(req.getParameter("note")).thenReturn("this is my note from user1");
    action.doSaveNote(req, rsp);
    assertEquals("default note", "this is my note from user1", resource.getNote());

    // switch to other user with reserve permission
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user2.impersonate2());
    when(req.getParameter("note")).thenReturn("this is my note from user2");
    action.doSaveNote(req, rsp);
    assertEquals("default note", "this is my note from user2", resource.getNote());

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

  //---------------------------------------------------------------------------
  @Test
  public void testDoSteal() throws IOException, ServletException {

    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();
    LockableResource resource = null;

    // nobody (system user)
    // system must be permitted to create resource
    resource = this.createResource("resource1");
    // when the resource is not reserved, the doSteal action reserve it for you
    action.doSteal(req, rsp);
    assertTrue("is reserved by system", resource.isReserved());

    // somebody
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
    action.doReserve(req, rsp);
    assertTrue("is reserved by user1 ", resource.isReserved());
    assertThrows(AccessDeniedException3.class, () -> action.doSteal(req, rsp));
    assertTrue("still reserved by user1", resource.isReserved());

    // switch as admin and reset
    SecurityContextHolder.getContext().setAuthentication(this.admin.impersonate2());
    action.doReset(req, rsp);
    assertFalse("unreserved", resource.isReserved());

    // switch to user1 and reserve it
    action.doReserve(req, rsp);
    assertTrue("is reserved by user1", resource.isReserved());

    // switch to user with STEAL permission
    SecurityContextHolder.getContext().setAuthentication(this.steal_user.impersonate2());
    action.doSteal(req, rsp);
    assertEquals("reserved by user", this.steal_user.getId(), resource.getReservedBy());

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

  //---------------------------------------------------------------------------
  @Test
  public void testDoUnlock() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testDoUnreserve() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetAllLabels() throws IOException, ServletException {
    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();

    this.LRM.createResourceWithLabel("resource-A", "resource-label-1 ");
    this.LRM.createResourceWithLabel("resource-B", "resource-label-1 resource-label-2");
    this.LRM.createResourceWithLabel(" resource-C", "resource-label-1 \n \t \r resource-label-2 resource-label-3");


    Set<String> expectedLabels = new HashSet<>();
    expectedLabels.add("resource-label-1");
    expectedLabels.add("resource-label-2");
    expectedLabels.add("resource-label-3");

    Set<String> labels = action.getAllLabels();
    assertEquals("check all labels", expectedLabels, labels);


    LockableResource getter = action.getResource("resource-C");
    assertEquals("check labels from resource-C", "resource-label-1 resource-label-2 resource-label-3", getter.getLabels());
    assertEquals("check resource name", "resource-C", getter.getName());
  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetAssignedResourceAmount() throws IOException, ServletException {
    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();

    // initial check, labels are not used
    assertEquals("initial check", 0, action.getAssignedResourceAmount("resource-A-label"));

    this.LRM.createResourceWithLabel("resource-A", "resource-label-1");
    this.LRM.createResourceWithLabel("resource-B", "resource-label-1 resource-label-2");
    this.LRM.createResourceWithLabel("resource-C", "resource-label-1 resource-label-2 resource-label-3");

    assertEquals("initial check", 3, action.getAssignedResourceAmount("resource-label-1"));
    assertEquals("initial check", 2, action.getAssignedResourceAmount("resource-label-2"));
    assertEquals("initial check", 1, action.getAssignedResourceAmount("resource-label-3"));

    // check label parsing
    assertEquals("check after reservation", 3, action.getAssignedResourceAmount("resource-label-1"));
    assertEquals("check after reservation", 1, action.getAssignedResourceAmount("resource-label-1 && resource-label-2 && resource-label-3"));
    assertEquals("check after reservation", 2, action.getAssignedResourceAmount("resource-label-1 && resource-label-2"));
    assertEquals("check after reservation", 2, action.getAssignedResourceAmount("resource-label-3 || resource-label-2"));


    // reserve one resource. Amount of assigned labels should change
    when(req.getParameter("resource")).thenReturn("resource-A");
    action.doReserve(req, rsp);
    
    assertEquals("check after reservation", 3, action.getAssignedResourceAmount("resource-label-1"));
    assertEquals("check after reservation", 2, action.getAssignedResourceAmount("resource-label-2"));
    assertEquals("check after reservation", 1, action.getAssignedResourceAmount("resource-label-3"));

    // reserve all resources. Amount of assigned labels should change
    when(req.getParameter("resource")).thenReturn("resource-B");
    action.doReserve(req, rsp);
    when(req.getParameter("resource")).thenReturn("resource-C");
    action.doReserve(req, rsp);

    assertEquals("check after reservation", 3, action.getAssignedResourceAmount("resource-label-1"));
    assertEquals("check after reservation", 2, action.getAssignedResourceAmount("resource-label-2"));
    assertEquals("check after reservation", 1, action.getAssignedResourceAmount("resource-label-3"));

    // defensive tests
    assertEquals("initial check", 0, action.getAssignedResourceAmount(""));
    assertEquals("initial check", 0, action.getAssignedResourceAmount(null));
    assertEquals("initial check", 0, action.getAssignedResourceAmount("resource-A-label "));
  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetFreeResourceAmount() throws IOException, ServletException {
    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();

    // initial check, labels are not used
    assertEquals("initial check", 0, action.getFreeResourceAmount("resource-A-label"));

    this.LRM.createResourceWithLabel("resource-A", "resource-label-1");
    this.LRM.createResourceWithLabel("resource-B", "resource-label-1 resource-label-2");
    this.LRM.createResourceWithLabel("resource-C", "resource-label-1 resource-label-2 resource-label-3");

    assertEquals("initial check", 3, action.getFreeResourceAmount("resource-label-1"));
    assertEquals("initial check", 2, action.getFreeResourceAmount("resource-label-2"));
    assertEquals("initial check", 1, action.getFreeResourceAmount("resource-label-3"));


    when(req.getParameter("resource")).thenReturn("resource-A");
    action.doReserve(req, rsp);
    
    assertEquals("check after label-1 is reserved", 2, action.getFreeResourceAmount("resource-label-1"));
    assertEquals("check after label-1 is reserved", 2, action.getFreeResourceAmount("resource-label-2"));
    assertEquals("check after label-1 is reserved", 1, action.getFreeResourceAmount("resource-label-3"));

    when(req.getParameter("resource")).thenReturn("resource-B");
    action.doReserve(req, rsp);
    when(req.getParameter("resource")).thenReturn("resource-C");
    action.doReserve(req, rsp);

    assertEquals("check after label-2 is reserved", 0, action.getFreeResourceAmount("resource-label-1"));
    assertEquals("check after label-2 is reserved", 0, action.getFreeResourceAmount("resource-label-2"));
    assertEquals("check after label-2 is reserved", 0, action.getFreeResourceAmount("resource-label-3"));

    // defensive tests
    assertEquals("defensive check", 0, action.getFreeResourceAmount(""));
    assertEquals("defensive check", 0, action.getFreeResourceAmount(null));
    assertEquals("defensive check", 0, action.getFreeResourceAmount("resource-A-label "));
  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetFreeResourcePercentage() throws IOException, ServletException {
    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();

    // initial check, labels are not used
    assertEquals("initial check", 0, action.getFreeResourcePercentage("resource-A-label"));

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

    assertEquals("initial check", 100, action.getFreeResourcePercentage("resource-label-1"));
    assertEquals("initial check", 100, action.getFreeResourcePercentage("resource-label-2"));
    assertEquals("initial check", 100, action.getFreeResourcePercentage("resource-label-3"));

    // reserve one resource. Amount of assigned labels should change
    when(req.getParameter("resource")).thenReturn("resource-A");
    action.doReserve(req, rsp);
    
    assertEquals("check after reservation", 90, action.getFreeResourcePercentage("resource-label-1"));
    assertEquals("check after reservation", 100, action.getFreeResourcePercentage("resource-label-2"));

    when(req.getParameter("resource")).thenReturn("resource-B");
    action.doReserve(req, rsp);
    assertEquals("check after reservation", 80, action.getFreeResourcePercentage("resource-label-1"));
    assertEquals("check after reservation", 88, action.getFreeResourcePercentage("resource-label-2"));

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

    assertEquals("check after all reserved", 0, action.getFreeResourcePercentage("resource-label-1"));
    assertEquals("check after all reserved", 0, action.getFreeResourcePercentage("resource-label-2"));
    assertEquals("check after all reserved", 0, action.getFreeResourcePercentage("resource-label-3"));

    // defensive tests
    assertEquals("defensive check", 0, action.getFreeResourcePercentage(""));
    assertEquals("defensive check", 0, action.getFreeResourcePercentage(null));
    assertEquals("defensive check", 0, action.getFreeResourcePercentage("resource-A-label "));
  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetIconFileName() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetNumberOfAllLabels() throws IOException, ServletException {
    when(req.getMethod()).thenReturn("POST");
    LockableResourcesRootAction action = new LockableResourcesRootAction();

    // initial check, labels are not used
    assertEquals("initial check", 0, action.getNumberOfAllLabels());

    this.LRM.createResourceWithLabel("resource-A", "resource-label-1");
    assertEquals("one resource with one label", 1, action.getNumberOfAllLabels());
    this.LRM.createResourceWithLabel("resource-B", "resource-label-1 resource-label-2");
    assertEquals("two resources with 2 labels", 2, action.getNumberOfAllLabels());
    this.LRM.createResourceWithLabel("resource-C", "resource-label-1 resource-label-2 resource-label-3");
    assertEquals("three resources with three labels", 3, action.getNumberOfAllLabels());
    this.LRM.createResourceWithLabel("resource-D", "resource-label-1 resource-label-2 resource-label-3");
    assertEquals("four resources with three labels", 3, action.getNumberOfAllLabels());
  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetUrlName() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetUserName() {

  }

  //---------------------------------------------------------------------------
  private LockableResource createResource(String resourceName) {
    this.LRM.createResourceWithLabel(resourceName, "label-" + resourceName);
    when(req.getParameter("resource")).thenReturn(resourceName);
    return LRM.fromName(resourceName);
  }
}
