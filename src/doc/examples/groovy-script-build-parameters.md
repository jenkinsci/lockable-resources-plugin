# Use build parameters in a Freestyle resource-selection script

A Freestyle job can use a build parameter to request either a specific test
controller or one from a pool. This example uses Jenkins' built-in **Choice
Parameter**; no additional parameter plugin or EnvInject configuration is needed.

## Create the resources

In **Manage Jenkins > Lockable Resources**, use **Add Resource** to create:

| Name | Labels |
| --- | --- |
| `rc:controller1` | `rc-validation-farm` |
| `rc:controller2` | `rc-validation-farm` |
| `rc:controller3` | `rc-validation-farm` |

Leave **Reserved by** empty so the resources are available to builds. These
resources represent test controllers; locking one does not select a Jenkins
agent or connect to the test controller.

## Configure the Freestyle job

1. Create a **Freestyle project**, or open an existing one's **Configure** page.
2. Select **This project is parameterized**, then **Add Parameter > Choice
   Parameter**. Set **Name** to `CONTROLLER` and **Choices** to the following,
   one per line. The first choice is the default:

   ```text
   LOCK_LABEL:rc-validation-farm
   controller1
   ```

3. Select **This build requires lockable resources**.
4. Leave **Resources** and **Label** empty. Select **Groovy Expression** and
   paste the script below into its script field. Select **Use Groovy Sandbox**.
5. Set **Number of resources to request** to `1` and **Reserved resources
   variable name** to `LOCKED_CONTROLLER`.
6. Add a build step from the next section, then save the job.

```groovy
def labelPrefix = 'LOCK_LABEL:'
if (CONTROLLER.startsWith(labelPrefix)) {
    def label = CONTROLLER.substring(labelPrefix.length())
    return resourceLabels.contains(label)
}
return resourceName == ('rc:' + CONTROLLER)
```

The expression returns `true` for each suitable resource and `false` for the
others. The plugin then selects one available match because the requested
number is `1`.

- `controller1` matches only the resource named `rc:controller1`, regardless
  of its labels.
- `LOCK_LABEL:rc-validation-farm` matches any resource with the exact label
  `rc-validation-farm`. The `LOCK_LABEL:` prefix is a convention implemented
  by this script, not special plugin syntax. This script checks one label,
  not a label expression such as `labelA && labelB`.

### Variables available to the expression

Build parameters are available directly by name, alongside these resource
bindings:

| Variable | Meaning |
| --- | --- |
| `CONTROLLER` | This build's choice parameter value, a string. |
| `resourceName` | Name of the resource being evaluated, such as `rc:controller1`. |
| `resourceLabels` | A `List<String>` of that resource's labels; `contains()` tests exact membership. |
| `resourceDescription` | The resource's configured description. |
| `resourceNote` | The resource's note. |
| `resourceLockReason` | Its current lock reason, if set. |

Use `CONTROLLER` directly, not Pipeline syntax such as `params.CONTROLLER` or
`env.CONTROLLER`. The binding contains build parameter values, not every build
environment variable. Avoid parameter names that collide with the resource
bindings above. `LOCKED_CONTROLLER` is available to build steps after selection,
not as an input to this expression.

### Script security

Resource-selection expressions run on the Jenkins controller under
[Script Security](https://plugins.jenkins.io/script-security/). With **Use
Groovy Sandbox** selected, operations must be permitted by the sandbox.
If Jenkins reports a rejected operation, an administrator can review the
pending signature under **Manage Jenkins > In-process Script Approval**.
When the sandbox is disabled, an administrator must approve the whole script;
editing it can require approval again. Approval problems prevent resource
selection; check the queue's blockage message and Jenkins system log.

## Use the selected resource in a build step

The plugin exposes the acquired resource name through the variable configured
above, `LOCKED_CONTROLLER`. With one resource, it contains a single name such as
`rc:controller2`; `LOCKED_CONTROLLER0` also contains that name. If the job
requests multiple resources, `LOCKED_CONTROLLER` contains their comma-separated
names and numbered variables identify each one.

For a Unix agent, add **Execute shell**:

```sh
: "${LOCKED_CONTROLLER:?No controller was locked; check the resource configuration}"
printf 'Requested: %s\nLocked: %s\n' "$CONTROLLER" "$LOCKED_CONTROLLER"
CONTROLLER_HOST=${LOCKED_CONTROLLER#rc:}
printf 'Controller host: %s\n' "$CONTROLLER_HOST"
# Run your test command here, using "$CONTROLLER_HOST".
```

For a Windows agent, use **Execute Windows batch command** instead:

```bat
@if "%LOCKED_CONTROLLER%"=="" (
    echo No controller was locked; check the resource configuration.
    exit /b 1
)
@echo Requested: %CONTROLLER%
@echo Locked: %LOCKED_CONTROLLER%
```

`CONTROLLER` keeps the original request. It is not overwritten with the
selected name. The shell example removes the `rc:` prefix into
`CONTROLLER_HOST` for commands that need a host name; this assumes the
remaining resource name is a reachable host in your environment. EnvInject
is not needed to read the selected name or do this conversion.

## Run and check the result

Choose **Build with Parameters**, select a value, and start the build.

| `CONTROLLER` choice | Expected `Locked:` output |
| --- | --- |
| `controller1` | `rc:controller1` |
| `LOCK_LABEL:rc-validation-farm` | One of `rc:controller1`, `rc:controller2`, or `rc:controller3`. |

If the named resource is busy, the build waits in the Jenkins queue for that
resource. For the label choice, it can use another available match; if all
matches are busy or reserved, it waits. The lock is held for the build and
released when the build completes.

**Check for an empty selection:** in the current Freestyle implementation,
an expression that matches no configured resources allows the build to proceed
without a resource, even when the requested number is `1`. A misspelled name
or label can therefore produce an empty `LOCKED_CONTROLLER`. Both build steps
above stop before doing work in that case. Create the resources first, keep the
choice values consistent with them, and retain this check in your test step.
