#!groovy

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.library.lockableresources.Resource;
import io.jenkins.library.lockableresources.ResourceLabel;
import io.jenkins.library.lockableresources.ResourcesManager as RM;

//-----------------------------------------------------------------------------
/**
*/
/** */
@CheckForNull
Resource call(String resourceName) {
  return new Resource(RM.getResourceOrDie(resourceName));
}

//-----------------------------------------------------------------------------
/**
*/
/** */
@CheckForNull
Resource find(String resourceName) {
  return new Resource(RM.getResource(resourceName));
}

//-----------------------------------------------------------------------------
/**
*/
/** */
List<Resource> find(List<String> resourceNames) {
  return RM.getResources(resourceNames);
}

//-----------------------------------------------------------------------------
/**
*/
/** */
List<Resource> find(ResourceLabel resourceLabel, int quantity = 0) {
  return RM.getResources(resourceLabel, ['quantity' : quantity]);
}

//-----------------------------------------------------------------------------
/**
*/
/** */
List<Resource> find(ResourceLabel resourceLabel, Map opts) {
  return RM.getResources(resourceLabel, opts);
}

//-----------------------------------------------------------------------------
/**
*/
/** */
List<Resource> find(int quantity, Closure closure) {
  return RM.getResources(closure, ['quantity' : quantity]);
}

//-----------------------------------------------------------------------------
/**
*/
/** */
List<Resource> find(Map opts, Closure closure) {
  return RM.getResources(closure, opts);
}

//-----------------------------------------------------------------------------
/**
*/
/** */
List<Resource> find(Closure closure) {
  return RM.getResources(closure, ['quantity' : 0]);
}

//-----------------------------------------------------------------------------
/** */
List<Resource> getAll() {
  return RM.getResources();
}

//------------------------------------------------------------------------------
/** */
boolean isFree(@NonNull String resourceName) {
  Resource resource = new Resource(resourceName);
  return resource.isFree();
}

