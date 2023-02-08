
package org.jenkins.plugins.lockableresources;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.Plugin;
import hudson.PluginWrapper;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;
import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever;

/**
 * Setup the shared library.
 *
 * Setup global shared library, so the end-user (administrator) has nothing to do.
 */
public final class SetupSharedLibrary {
  private static final Logger LOG = Logger.getLogger(SetupSharedLibrary.class.getName());

  private SetupSharedLibrary() {}

  @Initializer(after = InitMilestone.SYSTEM_CONFIG_LOADED)
  public static void setSharedLib() {
    LOG.log(Level.FINE, "lockable-resources-plugin configure shared libraries");
    
    final String preferredLibName = "lockable-resources-shared-library";
    GlobalLibraries gl = GlobalLibraries.get();
    List<LibraryConfiguration> libs = gl.getLibraries();
    boolean isAdded = false;
    for (LibraryConfiguration lib : libs) {
      if (lib != null && lib.getName().equals(preferredLibName)) {
        // our release tags contains ., git branch can not contains .
        // this check will allow to set as default som branch like 'hot-fix-nr1'
        // or 'master' or what ever.
        if (lib.getDefaultVersion().contains(".")) {
          lib.setDefaultVersion(myCurrentVersion());
        }
        isAdded = true;
        return;
      }
    }

    if (!isAdded) {
      GitSCMSource scm = new GitSCMSource("https://github.com/jenkinsci/lockable-resources-plugin");
      SCMSourceRetriever retriever = new SCMSourceRetriever(scm);
      retriever.setLibraryPath("shared-library/");
      LibraryConfiguration ourSharedLib = new LibraryConfiguration(preferredLibName, retriever);
      // scm.setDefaultVersion("master");
      ourSharedLib.setDefaultVersion(myCurrentVersion());
      ourSharedLib.setAllowVersionOverride(true);
      libs.add(ourSharedLib);
    }
    gl.setLibraries(libs);
  }

  private static String myCurrentVersion() {
    String myVersion = "";
    for (PluginWrapper plugin : Jenkins.get().pluginManager.getPlugins()) {
      if (plugin.getShortName().equals("lockable-resources")) {
        if (!plugin.getVersion().startsWith("999999-SNAPSHOT")) {
          myVersion = plugin.getVersion();
        }
      }
    }

    return myVersion;
  }
}
