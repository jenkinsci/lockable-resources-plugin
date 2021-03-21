/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, Aki Asikainen. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.EnvVars;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.CheckForNull;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.util.SerializableSecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;

public class LockableResourcesStruct implements Serializable {

  // Note to developers: if the set of selection criteria variables evolves,
  // do not forget to update LockableResourcesQueueTaskDispatcher.java with
  // class BecauseResourcesLocked method getShortDescription() for user info.
  public List<LockableResource> required;
  public String label;
  public String requiredVar;
  public String requiredNumber;

  @CheckForNull private final SerializableSecureGroovyScript serializableResourceMatchScript;

  @CheckForNull private transient SecureGroovyScript resourceMatchScript;

  public LockableResourcesStruct(RequiredResourcesProperty property, EnvVars env) {
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

    requiredNumber = env.expand(property.getResourceNumber());
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
      resourceMatchScript = serializableResourceMatchScript.rehydrate();
    }
    return resourceMatchScript;
  }

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

  private static final long serialVersionUID = 1L;
}
