#!groovy
package io.jenkins.library.lockableresources;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import java.io.Serializable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

//-----------------------------------------------------------------------------
// !!!!! do not use outside this library !!!!
class ResourceLabel implements Serializable {

  /** This label will be used for all node-resources.*/
  public static final String NODE_LABEL = 'node';
  private String name;

  //---------------------------------------------------------------------------
  public ResourceLabel(@NonNull String name) {
    this.name = Util.fixNull(name);
  }

  //---------------------------------------------------------------------------
  /** Convert to String
  */
  @NonNull
  @Restricted(NoExternalUse.class)
  public String toString() {
    return this.name;
  }

  //---------------------------------------------------------------------------
  /** Get label name.
  */
  @NonNull
  @Restricted(NoExternalUse.class)
  public String getName() {
    return name;
  }
}