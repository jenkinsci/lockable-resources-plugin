/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import static java.text.DateFormat.MEDIUM;
import static java.text.DateFormat.SHORT;

import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Binding;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.model.Run;
import hudson.model.User;
import hudson.model.labels.LabelAtom;
import hudson.tasks.Mailer.UserProperty;
import java.io.Serializable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean(defaultVisibility = 999)
public class LockableResource extends AbstractDescribableImpl<LockableResource>
  implements Serializable {

  private static final Logger LOGGER = Logger.getLogger(LockableResource.class.getName());
  public static final int NOT_QUEUED = 0;
  private static final int QUEUE_TIMEOUT = 60;
  public static final String GROOVY_LABEL_MARKER = "groovy:";

  private final String name;
  private String description = "";
  /** @deprecated use labelsAsList instead due performance.
   */
  @Deprecated private transient String labels = null;
  private List<String> labelsAsList = new ArrayList<>();
  private String reservedBy = null;
  private Date reservedTimestamp = null;
  private String note = "";

  /**
   * Track that a currently reserved resource was originally reserved
   * for someone else, or locked for some other job, and explicitly
   * taken away - e.g. for SUT post-mortems while a test job runs.
   * Currently this does not track "who" it was taken from nor intend
   * to give it back - just for bookkeeping and UI button naming.
   * Cleared when the resource is unReserve'd.
   */
  private boolean stolen = false;

  /**
   * We can use arbitrary identifier in a temporary lock (e.g. a commit hash of
   * built/tested sources), and not overwhelm Jenkins with lots of "garbage" locks.
   * Such locks will be automatically removed when freed, if they were not
   * explicitly declared in the Jenkins Configure System page.
   * If an originally ephemeral lock is later defined in configuration, it becomes
   * a usual persistent lock. If a "usual" lock definition is deleted while it is
   * being held, it becomes ephemeral and will disappear when freed.
   */
  private boolean ephemeral;
  private List<LockableResourceProperty> properties = new ArrayList<>();

  private long queueItemId = NOT_QUEUED;
  private String queueItemProject = null;
  private transient Run<?, ?> build = null;
  // Needed to make the state non-transient
  private String buildExternalizableId = null;
  private long queuingStarted = 0;

  private static final long serialVersionUID = 1L;

  private transient boolean isNode = false;

  /**
   * Was used within the initial implementation of Pipeline functionality using {@link LockStep},
   * but became deprecated once several resources could be locked at once. See queuedContexts in
   * {@link LockableResourcesManager}.
   *
   * @deprecated Replaced with LockableResourcesManager.queuedContexts (since 1.11)
   */
  @Deprecated private List<StepContext> queuedContexts = new ArrayList<>();

  /** @deprecated Use single-argument constructor instead (since 1.8) */
  @Deprecated
  @ExcludeFromJacocoGeneratedReport
  public LockableResource(String name, String description, String labels, String reservedBy, String note) {
    // todo throw exception, when the name is empty
    // todo check if the name contains only valid characters (no spaces, new lines ...)
    this.name = name;
    this.setDescription(description);
    this.setLabels(labels);
    this.setReservedBy(reservedBy);
    this.setNote(note);
  }

  @DataBoundConstructor
  public LockableResource(String name) {
    this.name = Util.fixNull(name);
    // todo throw exception, when the name is empty
    // todo check if the name contains only valid characters (no spaces, new lines ...)
  }

  protected Object readResolve() {
    if (queuedContexts == null) { // this field was added after the initial version if this class
      queuedContexts = new ArrayList<>();
    }
    this.repairLabels();
    return this;
  }

  private void repairLabels() {
    if (this.labels == null) {
      return;
    }

    LOGGER.fine("Repair labels for resource " + this);
    this.setLabels(this.labels);
    this.labels = null;
  }

  /** @deprecated Replaced with LockableResourcesManager.queuedContexts (since 1.11) */
  @Deprecated
  @ExcludeFromJacocoGeneratedReport
  public List<StepContext> getQueuedContexts() {
    return this.queuedContexts;
  }

  public boolean isNodeResource() {
    return isNode;
  }

  public void setNodeResource(boolean b) {
    isNode = b;
  }

  @Exported
  public String getName() {
    return name;
  }

  @Exported
  public String getDescription() {
    return description;
  }

  @DataBoundSetter
  public void setDescription(String description) {
    this.description = Util.fixNull(description);
  }

  @Exported
  public String getNote() {
    return this.note;
  }

  @DataBoundSetter
  public void setNote(String note) {
    this.note = Util.fixNull(note);
  }

  @DataBoundSetter
  public void setEphemeral(boolean ephemeral) {
    this.ephemeral = ephemeral;
  }

  @Exported
  public boolean isEphemeral() {
    return ephemeral;
  }

  /** Use getLabelsAsList instead
   * todo This function is marked as deprecated but it is still used in tests ans
   * jelly (config) files.
  */
  @Deprecated
  @Exported
  public String getLabels() {
    if (this.labelsAsList == null) {
      return "";
    }
    return String.join(" ", this.labelsAsList);
  }

  /** @deprecated no equivalent at the time.
   * todo It shall be created new one function selLabelsAsList() and use that one.
   * But it must be checked and changed all config.jelly files and
   * this might takes more time as expected.
   * That the reason why a deprecated function/property is still data-bound-setter
   */
  // @Deprecated can not be used, because of JCaC
  @DataBoundSetter
  public void setLabels(String labels) {
    // todo use label parser from Jenkins.Label to allow the same syntax
    this.labelsAsList = new ArrayList<>();
    for(String label : labels.split("\\s+")) {
      if (label == null || label.isEmpty()) {
        continue;
      }
      this.labelsAsList.add(label);
    }
  }

  /**
   * Get labels of this resource
   * @return List of assigned labels.
   */
  @Exported
  public List<String> getLabelsAsList() {
    return this.labelsAsList;
  }

  /**
   * Checks if the resource has label *labelToFind*
   * @param labelToFind Label to find.
   * @return {@code true} if this resource contains the label.
   */
  @Exported
  public boolean hasLabel(String labelToFind) {
    return this.labelsContain(labelToFind);
  }

  //----------------------------------------------------------------------------
  public boolean isValidLabel(String candidate, Map<String, Object> params) {
    if (candidate == null || candidate.isEmpty()) {
      return false;
    }

    if (labelsContain(candidate)) {
      return true;
    }

    final Label labelExpression = Label.parseExpression(candidate);
    Set<LabelAtom> atomLabels = new HashSet<>();
    for(String label : this.getLabelsAsList()) {
      atomLabels.add(new LabelAtom(label));
    }

    return labelExpression.matches(atomLabels);
  }

  //----------------------------------------------------------------------------
  /**
   * Checks if the resource contain label *candidate*.
   * @param candidate Labels to find.
   * @return {@code true} if resource contains label *candidate*
   */
  private boolean labelsContain(String candidate) {
    return this.getLabelsAsList().contains(candidate);
  }

  @Exported
  public List<LockableResourceProperty> getProperties() {
    return properties;
  }

  @DataBoundSetter
  public void setProperties(List<LockableResourceProperty> properties) {
    this.properties = (properties == null ? new ArrayList<>() : properties);
  }

  /**
   * Checks if the script matches the requirement.
   *
   * @param script Script to be executed
   * @param params Extra script parameters
   * @return {@code true} if the script returns true (resource matches).
   * @throws ExecutionException Script execution failed (e.g. due to the missing permissions).
   *     Carries info in the cause
   */
  @Restricted(NoExternalUse.class)
  public boolean scriptMatches(
    @NonNull SecureGroovyScript script, @CheckForNull Map<String, Object> params)
    throws ExecutionException {
    Binding binding = new Binding(params);
    binding.setVariable("resourceName", name);
    binding.setVariable("resourceDescription", description);
    binding.setVariable("resourceLabels", this.getLabelsAsList());
    binding.setVariable("resourceNote", note);
    try {
      Object result =
        script.evaluate(Jenkins.get().getPluginManager().uberClassLoader, binding, null);
      if (LOGGER.isLoggable(Level.FINE)) {
        LOGGER.fine(
          "Checked resource "
            + name
            + " for "
            + script.getScript()
            + " with "
            + binding
            + " -> "
            + result);
      }
      return (Boolean) result;
    } catch (Exception e) {
      throw new ExecutionException(
        "Cannot get boolean result out of groovy expression. See system log for more info", e);
    }
  }

  @Exported
  public Date getReservedTimestamp() {
    return reservedTimestamp == null ? null : new Date(reservedTimestamp.getTime());
  }

  @DataBoundSetter
  public void setReservedTimestamp(final Date reservedTimestamp) {
    this.reservedTimestamp = reservedTimestamp == null ? null : new Date(reservedTimestamp.getTime());
  }

  @Exported
  public String getReservedBy() {
    return reservedBy;
  }

  /** Return true when resource is free. False otherwise*/
  public boolean isFree() {
    return (!this.isLocked() && !this.isReserved() && !this.isQueued());
  }

  @Exported
  public boolean isReserved() {
    return reservedBy != null;
  }

  @Restricted(NoExternalUse.class)
  @CheckForNull
  public static String getUserName() {
    User current = User.current();
    if (current != null) {
      return current.getFullName();
    } else {
      return null;
    }
  }

  /**
   * Function check if the resources is reserved by currently logged user
   * @return true when reserved by current user, false otherwise.
   */
  @Restricted(NoExternalUse.class) // called by jelly
  public boolean isReservedByCurrentUser() {
    return (this.reservedBy != null && StringUtils.equals(getUserName(), this.reservedBy));
  }

  @Exported
  public String getReservedByEmail() {
    if (isReserved()) {
      UserProperty email = null;
      User user = Jenkins.get().getUser(reservedBy);
      if (user != null) email = user.getProperty(UserProperty.class);
      if (email != null) return email.getAddress();
    }
    return null;
  }

  public boolean isQueued() {
    this.validateQueuingTimeout();
    return queueItemId != NOT_QUEUED;
  }

  // returns True if queued by any other task than the given one
  public boolean isQueued(long taskId) {
    this.validateQueuingTimeout();
    return queueItemId != NOT_QUEUED && queueItemId != taskId;
  }

  public boolean isQueuedByTask(long taskId) {
    this.validateQueuingTimeout();
    return queueItemId == taskId;
  }

  public void unqueue() {
    queueItemId = NOT_QUEUED;
    queueItemProject = null;
    queuingStarted = 0;
  }

  @Exported
  public boolean isLocked() {
    return getBuild() != null;
  }

  /**
   * Resolve the lock cause for this resource. It can be reserved or locked.
   *
   * @return the lock cause or null if not locked
   */
  @CheckForNull
  public String getLockCause() {
    final DateFormat format = SimpleDateFormat.getDateTimeInstance(MEDIUM, SHORT);
    final String timestamp = (reservedTimestamp == null ? "<unknown>" : format.format(reservedTimestamp));
    if (isReserved()) {
      return String.format("[%s] is reserved by %s at %s", name, reservedBy, timestamp);
    }
    if (isLocked()) {
      return String.format("[%s] is locked by %s at %s", name, buildExternalizableId, timestamp);
    }
    return null;
  }

  @WithBridgeMethods(value = AbstractBuild.class, adapterMethod = "getAbstractBuild")
  public Run<?, ?> getBuild() {
    if (build == null && buildExternalizableId != null) {
      build = Run.fromExternalizableId(buildExternalizableId);
    }
    return build;
  }

  @Exported
  public String getBuildName() {
    if (getBuild() != null) return getBuild().getFullDisplayName();
    else return null;
  }

  public void setBuild(Run<?, ?> lockedBy) {
    this.build = lockedBy;
    if (lockedBy != null) {
      this.buildExternalizableId = lockedBy.getExternalizableId();
      setReservedTimestamp(new Date());
    } else {
      this.buildExternalizableId = null;
      setReservedTimestamp(null);
    }
  }

  public Task getTask() {
    Item item = Queue.getInstance().getItem(queueItemId);
    if (item != null) {
      return item.task;
    } else {
      return null;
    }
  }

  public long getQueueItemId() {
    this.validateQueuingTimeout();
    return queueItemId;
  }

  public String getQueueItemProject() {
    this.validateQueuingTimeout();
    return this.queueItemProject;
  }

  public void setQueued(long queueItemId) {
    this.queueItemId = queueItemId;
    this.queuingStarted = System.currentTimeMillis() / 1000;
  }

  public void setQueued(long queueItemId, String queueProjectName) {
    this.setQueued(queueItemId);
    this.queueItemProject = queueProjectName;
  }

  private void validateQueuingTimeout() {
    if (queuingStarted > 0) {
      long now = System.currentTimeMillis() / 1000;
      if (now - queuingStarted > QUEUE_TIMEOUT) unqueue();
    }
  }

  @DataBoundSetter
  public void setReservedBy(String userName) {
    this.reservedBy = Util.fixEmptyAndTrim(userName);
  }

  public void setStolen() {
    this.stolen = true;
  }

  @Exported
  public boolean isStolen() {
    return this.stolen;
  }

  public void reserve(String userName) {
    setReservedBy(userName);
    setReservedTimestamp(new Date());
  }

  public void unReserve() {
    setReservedBy(null);
    setReservedTimestamp(null);
    this.stolen = false;
  }

  public void reset() {
    this.unReserve();
    this.unqueue();
    this.setBuild(null);
  }

  /**
   * Copy unconfigurable properties from another instance. Normally, called after "lockable resource" configuration change.
   * @param sourceResource resource with properties to copy from
   */
  public void copyUnconfigurableProperties(final LockableResource sourceResource) {
    if (sourceResource != null) {
      setReservedTimestamp(sourceResource.getReservedTimestamp());
      setNote(sourceResource.getNote());
    }
  }

  /** Tell LRM to recycle this resource, including notifications for
   * whoever may be waiting in the queue so they can proceed immediately.
   * WARNING: Do not use this from inside the lock step closure which
   * originally locked this resource, to avoid nasty surprises!
   * Just stick with unReserve() and close the closure, if needed.
   */
  public void recycle() {
    try {
      List<LockableResource> resources = new ArrayList<>();
      resources.add(this);
      org.jenkins.plugins.lockableresources.LockableResourcesManager.get().
        recycle(resources);
    } catch (Exception e) {
      this.reset();
    }
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((name == null) ? 0 : name.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    LockableResource other = (LockableResource) obj;
    if (name == null) {
      if (other.name != null) return false;
    } else if (!name.equals(other.name)) return false;
    return true;
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<LockableResource> {

    @NonNull
    @Override
    public String getDisplayName() {
      return Messages.LockableResource_displayName();
    }
  }
}
