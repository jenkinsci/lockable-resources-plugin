package org.jenkins.plugins.lockableresources.actions;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;


import hudson.model.Item;
import hudson.model.User;
import hudson.security.AccessDeniedException3;
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
    this.admin = User.getById(this.ADMIN, true);

    j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
    this.j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
      .grant(Jenkins.READ).everywhere().to(this.USER)
      .grant(Jenkins.READ, Item.CONFIGURE).everywhere().to(this.USER_WITH_CONFIGURE_PERM)
      .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.RESERVE).everywhere().to(this.USER_WITH_RESERVE_PERM)
      .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.RESERVE).everywhere().to(this.USER_WITH_RESERVE_PERM_2)
      .grant(Jenkins.READ, Item.CONFIGURE, LockableResourcesRootAction.STEAL).everywhere().to(this.USER_WITH_STEAL_PERM)
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
    assertThrows(AccessDeniedException3.class, () -> action.doReserve(req, rsp));
    assertFalse("user without permission", resource.isReserved());

    // switch to suer with reserve permission
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());

    assertFalse("is free", resource.isReserved());
    action.doReserve(req, rsp);
    assertEquals("reserved by user", this.reserve_user1.getId(), resource.getReservedBy());

    // try to reassign as other user
    // it shall not changes, because the second user has not steal permission
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user2.impersonate2());
    assertThrows(AccessDeniedException3.class, () -> action.doReassign(req, rsp));
    assertEquals("still reserved by user", this.reserve_user1.getId(), resource.getReservedBy());

    // switch to user who has reserved the resource and try to reassign
    // The user can no do any action here, because he has no STEAL permission
    SecurityContextHolder.getContext().setAuthentication(this.reserve_user1.impersonate2());
    assertThrows(AccessDeniedException3.class, () -> action.doReassign(req, rsp));
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
    assertTrue("is reserved by system ", resource.isReserved());

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
  public void testDoReset() {
  }

  //---------------------------------------------------------------------------
  @Test
  public void testDoSaveNote() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testDoSteal() {

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
  public void testGetAllLabels() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetApi() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetAssignedResourceAmount() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetDisplayName() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetFreeResourceAmount() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetFreeResourcePercentage() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetIconFileName() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetNumberOfAllLabels() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetResource() {

  }

  //---------------------------------------------------------------------------
  @Test
  public void testGetResources() {

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
