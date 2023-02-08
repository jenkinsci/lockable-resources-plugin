#!groovy


import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.library.lockableresources.Resource;
import io.jenkins.library.lockableresources.ResourcesManager;
import io.jenkins.library.lockableresources.Utils;

//-----------------------------------------------------------------------------
void call(@NonNull String resourceName, @NonNull Closure closure) {
  lockResource(resourceName, [:], closure);
}

//-----------------------------------------------------------------------------
// createOnDemand: create resource when does not exists
// /** */
void call(@NonNull String resourceName, @NonNull Map opts, @NonNull Closure closure) {
  Resource resource = new Resource(resourceName);
  opts = Utils.fixNullMap(opts);

  if (opts.createOnDemand != null && !ResourcesManager.resourceExists(resourceName)) {
    final Map props = (opts.createOnDemand instanceof Map) ? opts.createOnDemand : [:];
    resource.create(props);
    opts.remove('createOnDemand');
  }
  _lock(resource, opts, closure);
}

//-----------------------------------------------------------------------------
// createOnDemand: create resource when does not exists
// /** */
void call(@NonNull List<String> resourceNames, @NonNull Map opts, @NonNull Closure closure) {
  lockResources(lockableResource.find(resourceNames), opts, closure);
}

//-----------------------------------------------------------------------------
void lockResources(@NonNull List<Resource> resources, @NonNull Map opts, @NonNull Closure closure) {

  opts = Utils.fixNullMap(opts);
  if (opts.createOnDemand) {
    for(Resource resource : resources) {

      if (!resource.exists()) {
        final Map props = (opts.createOnDemand instanceof Map) ? opts.createOnDemand : [:];
        resource.create(props);
      }
    }
  }
  opts.remove('createOnDemand');
  _multipleLock(resources, opts, closure);
}

//------------------------------------------------------------------------------
void _fixDefaultOpts(Map opts) {
  if (opts.variable == null) {
    opts.variable = 'LOCKED_RESOURCE';
  }
  if (opts.inversePrecedence == null) {
    opts.inversePrecedence = false;
  }
  if (opts.skipIfLocked == null) {
    opts.skipIfLocked = false;
  }
}

//------------------------------------------------------------------------------
// /** */
void _lock(@NonNull String resourceName, @NonNull Map opts, @NonNull Closure closure) {
  _lock(new Resource(resourceName), opts, closure);
}


//------------------------------------------------------------------------------
// /** */
void _lock(@NonNull Resource resource, @NonNull Map opts, @NonNull Closure closure) {

  _fixDefaultOpts(opts);

  try {
    if (opts.beforeLock != null) {
      opts.beforeLock(resource);
    }

    lock(
      resource: resource.getName(),
      variable: opts.variable,
      inversePrecedence: opts.inversePrecedence,
      skipIfLocked: opts.skipIfLocked
    ) {
      _insideLock(resource, opts, closure);
    }
    if (opts.afterRelease != null) {
      opts.afterRelease(resource);
    }
  } catch (error) {
    boolean accepted = false;
    if (opts.onFailure != null) {
      accepted = opts.onFailure(resource, error)
    }
    if (!accepted) {
      // not handled exception
      throw error;
    }
  }
}

//------------------------------------------------------------------------------
// /** */
void _multipleLock(@NonNull List<Resource> resources, @NonNull Map opts, @NonNull Closure closure) {

  _fixDefaultOpts(opts);

  def extra = [];
  for(Resource resource : resources) {
    extra.push(resource: resource.getName());
  }

  try {
    if (opts.beforeLock != null) {
      opts.beforeLock(resources);
    }

    lock(
      variable: opts.variable,
      inversePrecedence: opts.inversePrecedence,
      skipIfLocked: opts.skipIfLocked,
      extra : extra
    ) {
      _insideLock(resources, opts, closure);
    }
    if (opts.afterRelease != null) {
      opts.afterRelease(resources);
    }
  } catch (error) {
    boolean accepted = false;
    if (opts.onFailure != null) {
      accepted = opts.onFailure(resources, error)
    }
    if (!accepted) {
      // not handled exception
      throw error;
    }
  }
}

//------------------------------------------------------------------------------
// resource might be : Resource|List<Resource>
void _insideLock(@NonNull def resource, @NonNull Map opts, @NonNull Closure closure) {

  if (opts.timeout != null) {
    timeout(opts.timeout) {
      opts.remove('timeout');
      _insideLock(resource, opts, closure);
    }
    return;
  }

  if (opts.onLock != null) {
    opts.onLock(resource);
  }

  closure();

  if (opts.beforeRelease != null) {
    opts.beforeRelease(resource);
  }
}