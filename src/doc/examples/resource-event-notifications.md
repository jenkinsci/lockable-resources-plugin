# Resource Event Notifications

When a lockable resource changes state (locked, unlocked, reserved, etc.), the
plugin can notify you via a configurable Groovy callback script. This is useful
for sending Slack/email notifications, logging to external systems, or triggering
follow-up actions.

## Supported events

| Event | Triggered when |
|-------|---------------|
| `LOCKED` | A build acquires a resource |
| `UNLOCKED` | A build releases a resource |
| `RESERVED` | A user manually reserves a resource |
| `UNRESERVED` | A user manually unreserves a resource |
| `STOLEN` | A user steals a resource from another user |
| `REASSIGNED` | A resource is reassigned to a different user |
| `RESET` | A resource is reset to its default state |
| `RECYCLED` | A resource is recycled |
| `QUEUED` | A build is queued waiting for a resource |

## Configuration

Navigate to **Manage Jenkins → System → Lockable Resources Manager** and scroll
to the **Resource Event Callback** section.

| Setting | Description | Default |
|---------|-------------|---------|
| Groovy script | The script executed on each event | _(none)_ |
| Run asynchronously | Execute the callback in a background thread | `true` |
| Timeout (seconds) | Maximum time the callback may run | `30` |

## Binding variables

The following variables are available inside your Groovy callback script:

| Variable | Type | Description |
|----------|------|-------------|
| `resource` | `ResourceInfo` | Read-only snapshot of the affected resource |
| `event` | `String` | Event name (e.g. `"LOCKED"`, `"RESERVED"`) |
| `userName` | `String` | User who triggered the action (may be empty) |
| `buildName` | `String` | Build display name (may be empty) |

### ResourceInfo methods

- `resource.getName()` — resource name
- `resource.getDescription()` — resource description
- `resource.getNote()` — resource note
- `resource.getLabels()` — comma-separated labels
- `resource.getProperties()` — `Map<String, String>` of custom properties
- `resource.getProperty(key)` — single property value by key

## Example: Log events

```groovy
println "[LR-EVENT] ${event}: ${resource.getName()} (user: ${userName}, build: ${buildName})"
```

## Example: Send a Slack notification (via Slack plugin)

```groovy
if (event == "LOCKED" || event == "UNLOCKED") {
    def msg = "${event}: ${resource.getName()}"
    if (userName) msg += " by ${userName}"
    if (buildName) msg += " (${buildName})"

    def jenkins = jenkins.model.Jenkins.get()
    def job = jenkins.getItemByFullName("slack-notifier")
    if (job) {
        job.scheduleBuild2(0, new hudson.model.ParametersAction(
            new hudson.model.StringParameterValue("MESSAGE", msg)
        ))
    }
}
```

## Example: Filter by resource label

```groovy
if (resource.getLabels().contains("production") && event == "LOCKED") {
    println "ALERT: Production resource ${resource.getName()} locked by ${userName ?: buildName}"
}
```

## Java extension point

Plugin developers can implement `ResourceEventListener` to receive events
programmatically:

```java
@Extension
public class MyListener extends ResourceEventListener {
    @Override
    public void onEvent(ResourceEvent event, List<LockableResource> resources,
                        Run<?, ?> build, String userName) {
        // handle event
    }
}
```
