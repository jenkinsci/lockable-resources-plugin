/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, Aki Asikainen. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.util.SerializableSecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

public class LockableResourcesStruct implements Serializable {

  // Note to developers: if the set of selection criteria variables evolves,
  // do not forget to update LockableResourcesQueueTaskDispatcher.java with
  // class BecauseResourcesLocked method getShortDescription() for user info.
  public List<LockableResource> required;
  public String label;
  public String requiredVar;
  public String requiredNumber;
  public long queuedAt = 0;

  @CheckForNull private final SerializableSecureGroovyScript serializableResourceMatchScript;

  @SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED")
  @CheckForNull private transient SecureGroovyScript resourceMatchScript;

  private static final long serialVersionUID = 1L;

  public LockableResourcesStruct(RequiredResourcesProperty property, EnvVars env) {
    queuedAt = new Date().getTime();
    required = new ArrayList<>();

    LockableResourcesManager resourcesManager = LockableResourcesManager.get();
    for (String name : property.getResources()) {
      String resourceName = env.expand(name);
      if (resourceName == null) {
        continue;
      }
      resourcesManager.createResource(resourceName);
      LockableResource r = resourcesManager.fromName(resourceName);
      this.required.add(r);
    }

    label = env.expand(property.getLabelName());
    if (label == null) label = "";

    resourceMatchScript = property.getResourceMatchScript();
    serializableResourceMatchScript = new SerializableSecureGroovyScript(resourceMatchScript);

    requiredVar = property.getResourceNamesVar();

    requiredNumber = property.getResourceNumber();
    if (requiredNumber != null && requiredNumber.equals("0")) requiredNumber = null;
  }

  /**
   * Light-weight constructor for declaring a resource only.
   *
   * @param resources Resources to be required
   */
  public LockableResourcesStruct(@Nullable List<String> resources) {
    this(resources, null, 0);
  }

  public LockableResourcesStruct(
    @Nullable List<String> resources, @Nullable String label, int quantity, String variable) {
    this(resources, label, quantity);
    requiredVar = variable;
  }

  public LockableResourcesStruct(
    @Nullable List<String> resources, @Nullable String label, int quantity) {
    queuedAt = new Date().getTime();
    required = new ArrayList<>();
    if (resources != null) {
      for (String resource : resources) {
        LockableResource r = LockableResourcesManager.get().fromName(resource);
        if (r != null) {
          this.required.add(r);
        }
      }
    }

    this.label = label;
    if (this.label == null) {
      this.label = "";
    }

    this.requiredNumber = null;
    if (quantity > 0) {
      this.requiredNumber = String.valueOf(quantity);
    }

    // We do not support
    this.serializableResourceMatchScript = null;
    this.resourceMatchScript = null;
  }

  /**
   * Gets a system Groovy script to be executed in order to determine if the {@link
   * LockableResource} matches the condition.
   *
   * @return System Groovy Script if defined
   * @see
   *     LockableResource#scriptMatches(org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript,
   *     java.util.Map)
   * @since 2.1
   */
  @CheckForNull
  public SecureGroovyScript getResourceMatchScript() {
    if (resourceMatchScript == null && serializableResourceMatchScript != null) {
      // this is probably high defensive code, because
      resourceMatchScript = serializableResourceMatchScript.rehydrate();
    }
    return resourceMatchScript;
  }

  @Override
  public String toString() {
    return "Required resources: "
      + this.required
      + ", Required label: "
      + this.label
      + ", Required label script: "
      + (this.resourceMatchScript != null ? this.resourceMatchScript.getScript() : "")
      + ", Variable name: "
      + this.requiredVar
      + ", Number of resources: "
      + this.requiredNumber;
  }

  /** Returns timestamp when the resource has been added into queue.*/
  @Restricted(NoExternalUse.class) // used by jelly
  public Date getQueuedTimestamp() {
    return new Date(this.queuedAt);
  }

  /** Check if the queue takes too long.
    At the moment "too long" means over 1 hour.
  */
  @Restricted(NoExternalUse.class) // used by jelly
  public boolean takeTooLong() {
    return (new Date().getTime() - this.queuedAt) > 3600000L;
  }
}
