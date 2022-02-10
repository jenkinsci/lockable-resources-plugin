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
import hudson.EnvVars;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;
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
  public String anyOfLabels;
  public String allOfLabels;
  public String noneOfLabels;
  public String requiredVar;
  public String requiredNumber;

  @CheckForNull private final SerializableSecureGroovyScript serializableResourceMatchScript;

  @CheckForNull private transient SecureGroovyScript resourceMatchScript;

  public boolean hasLabelFilter()
  {
    return StringUtils.isNotBlank(anyOfLabels)
      || StringUtils.isNotBlank(allOfLabels)
      || StringUtils.isNotBlank(noneOfLabels);
  }

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

    allOfLabels = env.expand(property.getLabelName());
    if (allOfLabels == null) allOfLabels = "";

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
    this(resources, null, null, null, 0);
  }

  public LockableResourcesStruct(
    @Nullable List<String> resources, @Nullable String allOfLabels, int quantity, String variable) {
    this(resources, null, allOfLabels, null, quantity);
    requiredVar = variable;
  }

  public LockableResourcesStruct(
    @Nullable List<String> resources, @Nullable String anyOfLabels, @Nullable String allOfLabels, @Nullable String noneOfLabels, int quantity) {
    required = new ArrayList<>();
    if (resources != null) {
      for (String resource : resources) {
        LockableResource r = LockableResourcesManager.get().fromName(resource);
        if (r != null) {
          this.required.add(r);
        }
      }
    }

    this.anyOfLabels = Optional.ofNullable(anyOfLabels).orElse("");
    this.allOfLabels = Optional.ofNullable(allOfLabels).orElse("");
    this.noneOfLabels = Optional.ofNullable(noneOfLabels).orElse("");

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

  @Override
  public String toString() {
    return "Required resources: "
      + this.required
      + ", At least one of label: "
      + this.anyOfLabels
      + ", Required labels: "
      + this.allOfLabels
      + ", None of labels: "
      + this.noneOfLabels
      + ", Required label script: "
      + (this.resourceMatchScript != null ? this.resourceMatchScript.getScript() : "")
      + ", Variable name: "
      + this.requiredVar
      + ", Number of resources: "
      + this.requiredNumber;
  }

  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = Logger.getLogger(LockableResourcesStruct.class.getName());

  public static Predicate<List<String>> getLabelsMatchesPredicate(List<String> anyOfLabels, List<String> allOfLabels, List<String> noneOfLabels) {
    return resourceLabels -> {
      if (anyOfLabels.isEmpty() == false) {
        if (anyOfLabels.stream().noneMatch(l -> resourceLabels.contains(l))) {
          return false;
        }
      }
      if (allOfLabels.isEmpty() == false) {
        if (allOfLabels.stream().anyMatch(l -> resourceLabels.contains(l) == false)) {
          return false;
        }
      }
      if (noneOfLabels.isEmpty() == false) {
        if (noneOfLabels.stream().anyMatch(l -> resourceLabels.contains(l))) {
          return false;
        }
      }
      return true;
    };
  }

  public static List<String> labelStringToList(@Nullable String labels) {
    if (StringUtils.isBlank(labels))
      return Collections.emptyList();

    return Arrays.asList(labels.trim().split("\\s+"));
  }

  public static Predicate<String> getLabelsMatchesPredicate(String anyOfLabelsStr, String allOfLabelsStr, String noneOfLabelsStr) {
    List<String> anyOfLabels = labelStringToList(anyOfLabelsStr);
    List<String> allOfLabels = labelStringToList(allOfLabelsStr);
    List<String> noneOfLabels = labelStringToList(noneOfLabelsStr);
    return resourceLabelsStr -> getLabelsMatchesPredicate(anyOfLabels, allOfLabels, noneOfLabels).test(labelStringToList(resourceLabelsStr));
  }

  public Predicate<LockableResource> getLabelsPredicate() {
    return LockableResource -> getLabelsMatchesPredicate(anyOfLabels, allOfLabels, noneOfLabels).test(LockableResource.getLabels());
  }
}
