#!groovy
package io.jenkins.library.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import java.io.Serializable;
import java.util.Collections;
import io.jenkins.library.lockableresources.ResourcesManager as RM;
import io.jenkins.library.lockableresources.ResourceLabel;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager as LRM;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

//-----------------------------------------------------------------------------
/** Provide interface to org.jenkins.plugins.lockableresources.LockableResource

  Since LockableResource contains transient variables it is not serializable, and
  therefore not direct usable in the Jenkins pipelines. You will get many troubles
  by using NonCPS annotations.

  All public function may be used in Groovy pipelines, except marked by NoExternalUse
  annotation.
*/
class Resource implements Serializable {

  /** Lockable resource.

    private - because you shall use only public functions from this class,
    transient - because of NonCPS
   */
  private transient LockableResource resource;

  //---------------------------------------------------------------------------
  /** Init Resource from resource-name.

    When the resource does not exist, it will create new object, but it will be
    not added in to resource. See also Resource.create();
  */
  public Resource(@NonNull String resourceName) {
    this.resource = LRM.get().fromName(resourceName);
    if (this.resource == null) {
      this.resource = new LockableResource(resourceName);
    }
  }

  //---------------------------------------------------------------------------
  public Resource(@NonNull LockableResource resource) {
    this.resource = resource;
  }

  //----------------------------------------------------------------------------
  /** Create new resource.
    @parameter properties Resource properties as Map. To see possible values see
               also Resource.fromMap();

    @exception Raise exception, when resource exists.
  */
  public void create(Map properties = null) {
    if (this.exists()) {
      throw new Exception('Resource ' + this.getName() + ' currently exists!' +
                          'Therefore can not be created.');
    }
    if (properties != null) {
      this.fromMap(properties);
    }
    LRM.get().getResources().add(this.resource);
  }

  //----------------------------------------------------------------------------
  /** Save the resource.

   Exactly save all resources.
  */
  public void save() {
    RM.save();
  }

  //----------------------------------------------------------------------------
  /** Check if the resource exists or not.
    @return Returns true when exist, false otherwise.
  */
  public boolean exists() {
    return RM.resourceExists(this.getName());
  }

  //----------------------------------------------------------------------------
  /** Check if the resource is free.

    @return Returns true when resource is free for use, false otherwise.
  */
  public boolean isFree() {
    return (!this.resource.isLocked() && !this.resource.isReserved() && !this.resource.isQueued());
  }

  //----------------------------------------------------------------------------
  /** @return Returns resource name.
  */
  @NonNull 
  public String getName() {
    return this.resource.name;
  }

  //----------------------------------------------------------------------------
  /** Converts resource to string.
    @return resource object as String.
  */
  @NonNull 
  public String toString() {
    return this.getName();
  }

  //----------------------------------------------------------------------------
  /** Get resource description.
    @return Resource description or null when not set.
  */
  @CheckForNull
  public String getDescription() {
    return this.resource.description;
  }

  //----------------------------------------------------------------------------
  /** Set resource description.

    To store this changes permanently call Resource.save() afterwards.
    @param description Resource description to be set.
  */
  public void setDescription(String description) {
    this.resource.setDescription(description);
  }

  //----------------------------------------------------------------------------
  /** Get resource note.
    @return Resource note or null when not set.
  */
  @CheckForNull
  public String getNote() {
    return this.resource.note;
  }

  //----------------------------------------------------------------------------
  /** Set resource note.

    To store this changes permanently call Resource.save() afterwards.
    @param note Resource note to be set.
  */
  public void setNote(String note) {
    this.resource.setNote(note);
  }

  //----------------------------------------------------------------------------
  /** Checks if the resource is ephemeral (temporary).

    @return true when ephemeral, false otherwise.
  */
  public boolean isEphemeral() {
    return this.resource.isEphemeral();
  }

  //----------------------------------------------------------------------------
  /** Returns assigned labels.

    @return List of assigned resources. List might be empty, but newer null.
  */
  public List<ResourceLabel> getLabels() {
    List<ResourceLabel> list = [];
    for(String label : this.resource.labelsAsList) {
      list.push(new ResourceLabel(label));
    }
    return list;
  }

  //----------------------------------------------------------------------------
  /** Assign labels to resource.

    To store this changes permanently call Resource.save() afterwards.
    @parameter labels Labels shall be assigned to resource. To see possible
                      values see also Resource.toLabelsString();
  */
  public void setLabels(@NonNull def labels) {
    this.resource.setLabels(Resource.toLabelsString(labels));
  }

  //----------------------------------------------------------------------------
  /** Add label to assigned labels.

    In case the label is assigned just now, it will be ignored.
    To store this changes permanently call Resource.save() afterwards.
    @param label Label to be added.
  */
  public void addLabel(@NonNull ResourceLabel label) {
    if (this.resource.hasLabel(label.getName())) {
      return;
    }
    this.resource.labelsAsList.push(label.getName());
  }

  //----------------------------------------------------------------------------
  /** Remove label from assigned labels.

    To store this changes permanently call Resource.save() afterwards.
    @param label Label to be removed.
  */
  public void removeLabel(@NonNull ResourceLabel label) {
    if (!this.resource.hasLabel(label.getName())) {
      return;
    }
    this.resource.labelsAsList.remove(label.getName());
  }

  //----------------------------------------------------------------------------
  /** Check if the label is assigned to resource.

    @param label Label to be checked.
    @return true when the resource has the label.
  */
  public boolean hasLabel(@NonNull String label) {
    return this.resource.hasLabel(label);
  }

  //----------------------------------------------------------------------------
  /** Check if the label is assigned to resource.

    @param label Label to be checked.
    @return true when the resource has the label.
  */
  public boolean hasLabel(@NonNull ResourceLabel label) {
    return hasLabel(label.getName());
  }

  //----------------------------------------------------------------------------
  /** Convert resource to Map.

    This is used intern in the library for filtering, sorting ...
    @return Resource properties as Map
  */
  @Restricted(NoExternalUse.class)
  public Map toMap() {
    Map map = [
      'name' : this.getName(),
      'description' : this.getDescription(),
      'note' : this.getNote(),
      'labels' : this.getLabels(),
      'isFree' : this.isFree(),
      'reservedTimestamp' : this.resource.getReservedTimestamp(),
      'isLocked' : this.resource.isLocked(),
      'lockedBy' : this.resource.getBuildName(),
      'isReserved' : this.resource.isReserved(),
      'reservedBy' : this.resource.getReservedBy(),
      'isStolen' : this.resource.isStolen(),
      'isQueued' : this.resource.isQueued()
    ];

    // in case resource is a node, add the node properties too.
    if (this.hasLabel(ResourceLabel.NODE_LABEL)) {
      Computer computer = jenkins.model.Jenkins.instance.getComputer(this.getName());
      if (computer != null) {
        Map compMap = [:];
        compMap['isOnline'] = computer.isOnline();
        // object of OfflineCause or null
        compMap['offlineCause'] = computer.getOfflineCause();
        // The time when this computer last became idle.
        compMap['idleStartMilliseconds'] = computer.getIdleStartMilliseconds();
        // true if this computer has some idle executors that can take more workload
        compMap['isPartiallyIdle'] = computer.isPartiallyIdle();
        // true if all the executors of this computer are idle.
        compMap['isIdle'] = computer.isPartiallyIdle();
        // The current size of the executor pool for this computer.
        compMap['countExecutors'] = computer.countExecutors();
        // The number of executors that are doing some work right now.
        compMap['countBusy'] = computer.countBusy();
        // The number of idle {@link Executor}s that can start working immediately.
        compMap['countIdle'] = computer.countIdle();

        map['node'] = compMap;
      }
    }

    return map;
  }

  //----------------------------------------------------------------------------
  /** Convert Map to resource.
    
    @param map Properties:
      map.description Resource description.
      map.note Resource note.
      map.labels Resource labels.
  */
  @Restricted(NoExternalUse.class)
  public Map fromMap(@NonNull Map map) {
    this.resource.setDescription(map.description);
    this.resource.setNote(map.note);
    this.resource.setLabels(Resource.toLabelsString(map.labels));
  }

  //----------------------------------------------------------------------------
  /** Convert labels param to string.

    Original LockableResource has stored labels as String delimited by ' '.
    Therefore we need to convert List<String> or List<ResourceLabel> to string.
    Of cores String is supported too.
    Any other data types will raise a exception.
  */
  private static String toLabelsString(def labels) {
    String labelsString = "";
    if (labels == null) {
      return labelsString;
    } else if (labels instanceof String) {
      labelsString = labels;
    } else if (labels instanceof List<String>) {
      labelsString = labels.join(' ');
    } else if (labels instanceof List<ResourceLabel>) {
      for (ResourceLabel label : labels) {
        labelsString += label.name + ' ';
      }
    } else {
      throw(new Exception("Unsupported labels conversion: " + labels.class.name + " " + labels));
    }

    return labelsString.trim();
  }

  //----------------------------------------------------------------------------
  /** Check if assigned labels matches with label-expression.
  
    It use exact same matching as node() step.
    @return Return true when match, false otherwise.
  */
  @Restricted(NoExternalUse.class)
  public boolean matches(Label labelExpression) {
    List<String> ls = [];
    for(ResourceLabel resourceLabel : this.getLabels()) {
      ls.push(resourceLabel.getName());
    }

    return _matches(labelExpression, ls);
  }

  //----------------------------------------------------------------------------
  /// NonCPS because LabelAtom is not serializable
  @NonCPS
  private boolean _matches(Label labelExpression, def _labels) {
    Collection<LabelAtom> atomLabels =  [];
    for(String resourceLabel : _labels) {
      atomLabels.push(new LabelAtom(resourceLabel));
    }

    return labelExpression.matches(atomLabels);
  }
}
