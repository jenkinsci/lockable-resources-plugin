
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
public final class SharedLibrary {

  public final static String LIBRARY_NAME = "lockable-resources-shared-library";
  public final static String DEVELOP_VERSION = "999999-SNAPSHOT";

  private static final Logger LOG = Logger.getLogger(SharedLibrary.class.getName());

  private SharedLibrary() {}

  @Initializer(after = InitMilestone.SYSTEM_CONFIG_LOADED)
  public static void setSharedLib() {
    LOG.log(Level.FINE, "lockable-resources-plugin configure shared libraries");
    
    
    GlobalLibraries gl = GlobalLibraries.get();
    List<LibraryConfiguration> libs = gl.getLibraries();
    boolean isAdded = false;
    for (LibraryConfiguration lib : libs) {
      if (lib != null && lib.getName().equals(LIBRARY_NAME)) {
        // our release tags contains . (dot) and git branch can not contains .
        // this check will allow to set as default some branch like 'hot-fix-nr1'
        // or 'master' or 'what/ever'.
        if ((lib.getDefaultVersion() == null) || lib.getDefaultVersion().contains(".")) {
          lib.setDefaultVersion(getCurrentVersion());
        }
        isAdded = true;
        return;
      }
    }

    if (!isAdded) {
      GitSCMSource scm = new GitSCMSource("https://github.com/jenkinsci/lockable-resources-plugin");
      SCMSourceRetriever retriever = new SCMSourceRetriever(scm);
      retriever.setLibraryPath("shared-library/");
      LibraryConfiguration ourSharedLib = new LibraryConfiguration(LIBRARY_NAME, retriever);
      // scm.setDefaultVersion("master");
      ourSharedLib.setDefaultVersion(getCurrentVersion());
      ourSharedLib.setAllowVersionOverride(true);
      libs.add(ourSharedLib);
    }
    gl.setLibraries(libs);
  }

  public static String getCurrentVersion() {
    String myVersion = "";
    for (PluginWrapper plugin : Jenkins.get().pluginManager.getPlugins()) {
      if (plugin.getShortName().equals("lockable-resources")) {
        if (!plugin.getVersion().startsWith(DEVELOP_VERSION)) {
          myVersion = plugin.getVersion();
        }
      }
    }

    return myVersion;
  }
}
