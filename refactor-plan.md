# Lockable Resources Manager Refactoring Plan

**Created:** 2026-04-14  
**Status:** Brainstorming / Planning  
**Related Issue:** To be created

ATENTION: THIS DOCUMENT MUST BE REMOVED BEFORE WE MERGE THE CHAMGES INTO MASTER BRANCH


---

## Table of Contents

1. [Current Architecture](#current-architecture)
2. [Identified Problems](#identified-problems)
3. [Proposed Solutions](#proposed-solutions)
4. [Groups Concept](#groups-concept)
5. [Resource Pages](#resource-pages)
6. [Folder-Scoped Resources](#folder-scoped-resources)
7. [Node Integration & lockNode() Step](#node-integration--locknode-step)
8. [Multi-Instance Synchronization](#multi-instance-synchronization)
9. [New APIs](#new-apis)
10. [Migration Strategy](#migration-strategy)
11. [Implementation Phases](#implementation-phases)
12. [Open Questions](#open-questions)
13. [AI Engineering](#ai-engineering)
14. [Session Notes](#session-notes)

---

## Current Architecture

### Core Data Structure

```java
// LockableResourcesManager.java (line 60)
private List<LockableResource> resources;

// Candidate cache per queue item (line 63)
private final transient Cache<Long, List<LockableResource>> cachedCandidates =
    CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
```

### How Resources Are Found

1. **By Name** - `fromName(String resourceName)` - O(n) linear scan
2. **By Label** - `getResourcesWithLabel(String label)` - O(n) linear scan  
3. **By Script** - `getResourcesMatchingScript()` - O(n) with Groovy evaluation

### Synchronization

All operations use a single global lock:
```java
public static final Object syncResources = new Object();
```

### Typical Scale

- Small installations: ~50 resources
- Large installations: 100-1000 resources
- Heavy users: 1000+ resources, 1000+ queue items

---

## Identified Problems

### 1. O(n) Lookups
Every `fromName()` call iterates through all resources. With 1000 resources and frequent lookups, this adds up.

### 2. No Resource Grouping
All resources are in a flat list. Users with different teams/projects cannot organize resources logically.

### 3. Label Expression Complexity
Label expressions like `(ABC && DD) || !HH` require full scan - cannot be indexed efficiently.

### 4. No Resource Statistics
No built-in way to track:
- How often a resource is locked
- Average lock duration
- Queue wait times
- Which jobs use which resources

### 5. No Resource Detail Page
Unlike Jenkins nodes (`/computer/<nodeName>`), resources have no dedicated page with history/config.

### 6. Single Global Lock
All operations go through `syncResources`, potential bottleneck at scale.

### 7. Performance at Scale
Performance must be a first-class concern throughout the refactoring:
- `proceedNextContext()` iterates the full queue on every unlock — O(queue × resources)
- `uncacheIfFreeing()` iterates all cache entries to find affected ones
- Groovy-based resource matching (`resourceMatchScript`) is expensive per evaluation
- Saving the full config on every lock/unlock generates I/O pressure at high throughput
- No profiling or benchmarking harness exists today to measure regressions

Every new feature (groups, node integration, multi-instance sync) must be evaluated
for its impact on hot paths.

### 8. No Multi-Instance Support ([#321](https://github.com/jenkinsci/lockable-resources-plugin/issues/321))
Large environments run multiple Jenkins controllers. Resources locked on
`jenkins1` are invisible to `jenkins2`, leading to conflicts when both
controllers share the same physical equipment.

### 9. No Native Node Integration
In many use cases, lockable resources represent Jenkins **nodes** (agents).
Today users must manually create a lockable resource for each node and keep
the names / labels in sync. There is no built-in way to say
"lock the node, not just a virtual resource."

---

## Proposed Solutions

### Phase 1: Add Name Index (Low Risk)

Add a `ConcurrentHashMap` index for O(1) name lookup:

```java
// Alongside existing list
private List<LockableResource> resources;

// New index - always in sync with list
private final ConcurrentHashMap<String, LockableResource> resourcesByName = new ConcurrentHashMap<>();
```

**Benefits:**
- `fromName()` becomes O(1)
- Backward compatible - list still exists for iteration
- No config file changes

**Implementation:**
```java
public LockableResource fromName(String name) {
    return resourcesByName.get(name);
}

// Keep index in sync
public boolean addResource(LockableResource resource) {
    synchronized (syncResources) {
        if (resourcesByName.containsKey(resource.getName())) {
            return false;
        }
        resources.add(resource);
        resourcesByName.put(resource.getName(), resource);
        // ...
    }
}
```

### Phase 2: Label Index (Medium Risk)

Add index for single-label lookups:

```java
// Map: label -> Set of resources with that label
private final ConcurrentHashMap<String, Set<LockableResource>> resourcesByLabel = new ConcurrentHashMap<>();
```

**Note:** This only helps simple label queries. Complex expressions like `(A && B) || !C` still need full scan.

### Phase 3: Replace Guava Cache with Caffeine (Low Risk)

```java
// Current (Guava)
private final Cache<Long, List<LockableResource>> cachedCandidates =
    CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();

// Proposed (Caffeine) - better performance, same API
private final Cache<Long, List<LockableResource>> cachedCandidates =
    Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
```

---

## Performance Strategy

### Principles

1. **Measure first** — add a JMH or Jenkins-benchmark harness before optimizing.
2. **Profile hot paths** — `proceedNextContext()`, `tryLock()`, `queueContext()`,
   `uncacheIfFreeing()`, `save()` are the most frequent callers under load.
3. **Budget per feature** — every new feature (groups, node integration,
   multi-instance sync) must document its expected cost on the hot path.
4. **Regression gate** — CI should run a lightweight load scenario and fail if
   median lock-cycle time regresses by more than 10%.

### Concrete Targets

| Area | Current | Target |
|------|---------|--------|
| `fromName()` | O(n) scan | O(1) via name index |
| `getResourcesWithLabel()` | O(n) scan | O(1) for single labels via label index |
| `uncacheIfFreeing()` | O(cache size) | O(1) via reverse index (resource → cache keys) |
| `save()` under contention | Sync write per event | Coalesced async write (already exists, verify effective) |
| Queue processing | O(queue × resources) | Evaluate read-write lock, partitioned queues |
| Groovy script matching | Costly per call | Cache script compilation, limit evaluation frequency |

---

## Groups Concept

### Motivation

Teams/projects want isolated resource pools:
- Team A has PLC resources
- Team B has printers
- Common pool for shared resources

### Proposed API

```groovy
// Lock from specific group
lock(group: 'plc', label: 'S7') {
    // ...
}

// Default group is 'common'
lock(label: 'printer') {
    // Equivalent to: group: 'common', label: 'printer'
}

// Lock from any group (explicit)
lock(group: '*', label: 'S7')
```

### Data Model

```java
public class ResourceGroup {
    private String name;
    private String description;
    private List<LockableResource> resources;
    // Optional: access control, quotas
}

public class LockableResourcesManager {
    // Replace flat list with groups
    private Map<String, ResourceGroup> groups = new ConcurrentHashMap<>();
    
    // Convenience: all resources (computed view)
    public List<LockableResource> getResources() {
        return groups.values().stream()
            .flatMap(g -> g.getResources().stream())
            .collect(Collectors.toList());
    }
}
```

### UI Changes

- Group selector in resource configuration
- Group filter on resources page
- Group statistics

### Config File Changes

**Current format:**
```xml
<org.jenkins.plugins.lockableresources.LockableResourcesManager>
  <resources>
    <resource>
      <name>resource1</name>
      <labels>S7 plc</labels>
    </resource>
  </resources>
</org.jenkins.plugins.lockableresources.LockableResourcesManager>
```

**New format:**
```xml
<org.jenkins.plugins.lockableresources.LockableResourcesManager>
  <groups>
    <group>
      <name>plc</name>
      <description>PLC test equipment</description>
      <resources>
        <resource>
          <name>resource1</name>
          <labels>S7</labels>
        </resource>
      </resources>
    </group>
    <group>
      <name>common</name>
      <resources>
        <!-- migrated resources without explicit group -->
      </resources>
    </group>
  </groups>
</org.jenkins.plugins.lockableresources.LockableResourcesManager>
```

---

## Resource Pages

### Inspiration

Jenkins nodes have dedicated pages at `/computer/<nodeName>` with:
- Current status
- History
- Configuration
- Statistics

### Proposed URL Structure

```
/lockable-resources/                     # Main listing (existing)
/lockable-resources/<resourceName>/      # Resource detail page (new)
/lockable-resources/<resourceName>/configure  # Configuration (new)
/lockable-resources/<resourceName>/history    # Lock history (new)
```

### Resource Detail Page Content

1. **Status Panel**
   - Current state (free/locked/reserved/queued)
   - Current holder (build link or user)
   - Lock duration
   - Labels, description, properties

2. **History Table** (persisted)
   - Last N lock events
   - Timestamp, duration, build/user, outcome
   
3. **Statistics**
   - Total lock count
   - Average lock duration
   - Average queue wait time
   - Top 10 jobs by usage

4. **Actions**
   - Reserve/Unreserve
   - Steal
   - Reset
   - Configure (link)

### Data Storage for History

Option A: Append to resource XML (simple, grows unbounded)
```xml
<resource>
  <name>resource1</name>
  <history>
    <event>
      <timestamp>2026-04-14T10:30:00</timestamp>
      <type>LOCK</type>
      <build>job/test/42</build>
      <duration>PT5M30S</duration>
    </event>
  </history>
</resource>
```

Option B: Separate history file per resource (better scaling)
```
work/lockable-resources/
  history/
    resource1.json
    resource2.json
```

Option C: Use Jenkins fingerprint system (complex but integrated)

---

## Folder-Scoped Resources

### Issue

[#186](https://github.com/jenkinsci/lockable-resources-plugin/issues/186) /
[JENKINS-51725](https://issues.jenkins.io/browse/JENKINS-51725) /
[JENKINS-49004](https://issues.jenkins.io/browse/JENKINS-49004)

### Motivation

Large Jenkins installations use the Folders plugin (`cloudbees-folder`) to
organize jobs by team or project. Folder admins should be able to manage their
own lockable resources without requiring global Jenkins admin rights — the same
way credentials are scoped to folders today.

### How It Fits with Groups

Folder-scoped resources are a **natural extension of the Groups concept**:

- A **Group** is a logical collection of resources (e.g. "plc", "printers").
- A **Folder scope** determines *where* a group is visible.
- Combining both: a group can be defined at global level OR at a folder level.
- Resources in a folder-scoped group are only visible to jobs inside that
  folder (and its children), following the same hierarchy-walk pattern as
  credentials.

```
Jenkins (global)
├── Group: "common"          ← visible to all jobs
├── Group: "shared-printers" ← visible to all jobs
│
├── Folder: TeamA/
│   ├── Group: "plc"         ← visible only to TeamA/* jobs
│   └── Folder: SubTeam/
│       └── (inherits TeamA's "plc" group + global groups)
│
└── Folder: TeamB/
    └── Group: "plc"         ← separate "plc", visible only to TeamB/*
```

### Architecture Design

#### Extension Point: `AbstractFolderProperty`

Follow the proven pattern used by credentials, Kubernetes plugin, HashiCorp
Vault plugin, etc.:

```java
@Extension
@OptionalDependency("com.cloudbees.hudson.plugins.folder")
public class LockableResourcesFolderProperty
        extends AbstractFolderProperty<AbstractFolder<?>> {

    private List<ResourceGroup> groups = new ArrayList<>();

    // Folder admins can configure lockable resource groups here
    public List<ResourceGroup> getGroups() { return groups; }

    @Extension
    public static class DescriptorImpl
            extends AbstractFolderPropertyDescriptor { }
}
```

#### Resource Resolution: Hierarchy Walk

When `lock()` is called, resolve resources by walking up the folder tree
(like `CredentialsProvider.listCredentialsInItem()`):

```java
public List<LockableResource> resolveResources(
        Run<?, ?> run, String group, String label) {

    List<LockableResource> result = new ArrayList<>();
    ItemGroup<?> parent = run.getParent().getParent();

    // Walk up the folder hierarchy
    while (parent != null) {
        if (parent instanceof AbstractFolder<?> folder) {
            LockableResourcesFolderProperty prop =
                folder.getProperties()
                      .get(LockableResourcesFolderProperty.class);
            if (prop != null) {
                result.addAll(prop.getMatchingResources(group, label));
            }
        }
        parent = (parent instanceof Item item)
            ? item.getParent() : null;
    }

    // Finally, check global resources
    result.addAll(
        LockableResourcesManager.get().getMatchingResources(group, label));

    return result;
}
```

#### Scope Enum on ResourceGroup

```java
public enum ResourceScope {
    GLOBAL,  // visible everywhere (current behavior)
    FOLDER   // visible only to jobs in this folder and children
}
```

#### Optional Dependency

`cloudbees-folder` must be an **optional** dependency so the plugin still
works without it. Use `@OptionalDependency` or guard with
`Jenkins.get().getPlugin("cloudbees-folder") != null`.

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.jenkins-ci.plugins</groupId>
    <artifactId>cloudbees-folder</artifactId>
    <optional>true</optional>
</dependency>
```

### Pipeline DSL Changes

```groovy
// Existing — resolves from global + inherited folder groups
lock(label: 'S7') { ... }

// With explicit group — resolves group "plc" walking up hierarchy
lock(group: 'plc', label: 'S7') { ... }

// Explicit scope override — only look at the current folder
lock(group: 'plc', label: 'S7', scope: 'folder') { ... }

// Global only — skip folder hierarchy
lock(group: 'plc', label: 'S7', scope: 'global') { ... }
```

### UI Changes

1. **Folder configuration page** — new "Lockable Resources" section under
   folder properties (similar to "Folder Credentials"):
   - Add/remove resource groups
   - Add/remove resources within groups
   - Folder admins (not just Jenkins admins) can manage

2. **Global configuration** — unchanged, continues to manage global groups

3. **Resource listing page** — show scope indicator (global/folder path)
   for each resource

### Permissions

Leverage Jenkins folder-level permissions:
- `Item.CONFIGURE` on a folder → can manage that folder's lockable resources
- Global `Jenkins.ADMINISTER` → can manage global resources (unchanged)
- No new permission types needed initially

### Ephemeral Resources

When a `lock('nonexistent')` creates an ephemeral resource, where does it go?
- **Default:** global level (preserves current behavior)
- **Future option:** `lock('name', createIn: 'folder')` to create in the
  calling job's nearest folder scope
- **Configurable default** via folder property: "Create ephemeral resources
  in this folder's scope"

### Name Uniqueness

- Resource names must be **unique within a scope** (folder or global)
- Resources in different folder scopes CAN have the same name
- Resolution order (closest scope wins): current folder → parent folder →
  ... → global
- If ambiguous, `lock()` takes the first match walking up the hierarchy
- Users can disambiguate with `scope: 'global'` or `scope: 'folder'`

### Open Questions (Folder Scoping)

1. **Cross-folder locking?** Can a job in FolderA lock a resource defined
   in FolderB? Credentials say no. Same approach recommended.

2. **Move semantics?** When a job is moved between folders, its locks
   should be re-evaluated. What happens to active locks?

3. **Folder deletion?** When a folder is deleted, its scoped resources
   should be cleaned up. Need `AbstractFolder` lifecycle hooks.

4. **Should folder-scoped resources appear in the global listing?**
   Admin might want a full view. Consider a "show all scopes" toggle.

5. **Lock contention across scopes?** Two identically-named resources in
   different scopes are independent — no contention. Is this intuitive?

---

## Node Integration & lockNode() Step

### Motivation

In most industrial use cases, lockable resources represent **physical nodes**
(test rigs, PLCs, lab machines). Users must today:
1. Define a Jenkins agent for the node
2. Separately create a lockable resource with a matching name
3. Keep labels in sync manually

This is error-prone and redundant. The plugin should natively understand
Jenkins nodes.

### Proposed `lockNode()` Step

A new pipeline step with clear semantics — "lock this node exclusively":

```groovy
// Lock by node name
lockNode('lab-plc-07') {
    echo "Running on exclusive node"
}

// Lock by node label (picks one free node)
lockNode(label: 'S7-1500', quantity: 1) {
    node(env.LOCKED_NODE) {
        echo "Running on ${env.NODE_NAME}"
    }
}

// Combine with existing resource labels
lockNode(label: 'S7-1500', resourceLabel: 'plc', quantity: 1) {
    echo "Node + resource locked"
}
```

**Why a new step instead of extending `lock()`?**
- Clearer intent: users immediately understand they are locking a *node*
- Avoids overloading `lock()` with yet more parameters
- Can provide node-specific environment variables (`LOCKED_NODE`, `LOCKED_NODE_LABEL`)
- Can leverage Jenkins node availability checks (online/offline)

**Multi-node locking with parallel execution (decided):**
- When `quantity > 1`, all locked nodes receive the closure as **parallel stages**
- Offline node handling:
  - Prefer online nodes first when selecting candidates
  - Provide an option to exclude offline nodes entirely

### Implementation Options

| Option | Description | Pros | Cons |
|--------|-------------|------|------|
| A. Mirror node labels into lockable resource labels | `NodesMirror` already does this partially | Low effort, backward compatible | Still two separate lists |
| B. First-class node reference on `LockableResource` | Add `nodeName` field, auto-resolve from `Computer` | Single source of truth | Migration needed |
| C. `lockNode()` delegates to `lock()` internally | New step is syntactic sugar | Quick to implement | Might confuse users (two steps, same queue) |
| D. Separate node-lock queue | Independent of `lock()` queue | Clean separation | Duplicated locking logic |

**Recommendation:** Start with **Option C** (syntactic sugar) to validate UX,
then consider **Option B** for deeper integration.

### Node Labels vs Resource Labels

Jenkins already has a label system for nodes (`label: 'linux && x86_64'`).
We should support **both** independently:

| Feature | Node label | Resource label |
|---------|-----------|----------------|
| Defined on | Jenkins agent configuration | Lockable resource configuration |
| Expression syntax | Same (Jenkins `Label` class) | Same |
| Use case | Hardware capabilities | Logical grouping |
| Example | `S7-1500 && linux` | `plc production` |

```groovy
// Lock a node that matches BOTH a node label AND a resource label
lockNode(nodeLabel: 'S7-1500 && linux', resourceLabel: 'production') {
    // ...
}
```

---

## Multi-Instance Synchronization

### Problem ([#321](https://github.com/jenkinsci/lockable-resources-plugin/issues/321))

Large environments run multiple Jenkins controllers sharing the same physical
equipment. Resource state is local to each instance — there is no coordination.

### Architecture Options

| Option | Description | Pros | Cons |
|--------|-------------|------|------|
| **A. REST API (lockable-master)** | Designate one instance as "lockable-master"; slaves call its REST API before acquiring a lock | Simple concept, leverages existing Jenkins API | Single point of failure, network latency on every lock |
| **B. Shared database backend** | Replace XML config with a shared DB (PostgreSQL, Redis) | Strong consistency, proven technology | Heavy dependency, operational burden |
| **C. Distributed lock service** | Use etcd / ZooKeeper / Consul for lock coordination | Battle-tested distributed locking | External dependency, complex setup |
| **D. Peer-to-peer REST** | Each instance exposes a lock-status API; peers poll or push state | No central component | Consistency is hard, split-brain risk |

### Analysis of REST API Approach (Option A)

Original proposal from #321: a "lockable-master" instance holds the
authoritative state; "lockable-slave" instances interact via HTTP POST.

**Flow:**
1. `lockable-slave` checks local state — if resource is in use locally, wait
2. Send lock request to `lockable-master` REST API
3. If `lockable-master` returns 409 (conflict) → poll/retry
4. On success → acquire lock locally, run closure
5. On unlock → POST unlock to `lockable-master`, then free locally

**Concerns:**
- What happens when `lockable-master` is down? (need fallback / timeout policy)
- Latency on every lock/unlock adds up under load
- State can drift if network partitions occur
- Need heartbeat / lease mechanism to avoid stale locks

**Verdict:** REST API is a reasonable **starting point** because it requires
no external infrastructure. But it should be designed with a pluggable
`LockBackend` interface so the backend can later be swapped to a DB or
distributed lock service.

### Proposed Abstraction

```java
public interface LockBackend {
    /** Try to acquire. Returns true if granted. */
    boolean tryAcquire(String resourceName, String owner, Duration timeout);

    /** Release a previously acquired lock. */
    void release(String resourceName, String owner);

    /** Query current state. */
    LockState getState(String resourceName);

    /** Health check. */
    boolean isAvailable();
}

// Implementations:
// - LocalLockBackend       (current behavior, default)
// - RestMasterLockBackend  (Option A)
// - DatabaseLockBackend    (Option B, future)
```

---

## New APIs

### Resource Status API

```java
// Simple check - is resource completely free?
public boolean isFree(String resourceName);

// Detailed status
public ResourceStatus getStatus(String resourceName);

public enum ResourceState {
    FREE,
    LOCKED,
    RESERVED,
    QUEUED,
    LOCKED_AND_RESERVED  // stolen case
}

public class ResourceStatus {
    ResourceState state;
    Run<?, ?> build;        // if locked
    String reservedBy;       // if reserved
    long queueItemId;        // if queued
    Date stateChangedAt;
}
```

### Statistics API

```java
public ResourceStats getStats(String resourceName);
public Map<String, ResourceStats> getStats(String group, String label, ResourceState state);

public class ResourceStats {
    int lockCount;
    Duration totalLockTime;
    Duration avgLockTime;
    Duration avgQueueTime;
    Map<String, Integer> lockCountByJob;  // job -> count
}
```

### Pipeline Steps

```groovy
// Check if resource is free (non-blocking)
def free = lockableResource.isFree('resource1')

// Get resource info
def info = lockableResource.getInfo('resource1')
echo "State: ${info.state}, Labels: ${info.labels}"

// Get statistics
def stats = lockableResource.getStats('resource1')
echo "Locked ${stats.lockCount} times, avg duration: ${stats.avgLockTime}"
```

---

## Migration Strategy

### Principles

1. **Automatic migration** - old config loads into new structure seamlessly
2. **No user action required** - plugin handles upgrade transparently
3. **Backward compatible save** - consider if older plugin version needs to read

### Migration Code Pattern

```java
@Override
public void load() {
    // Read raw XML
    XmlFile configFile = getConfigFile();
    
    if (configFile.exists()) {
        Object loaded = configFile.read();
        
        if (needsMigration(loaded)) {
            migrateConfig(loaded);
            save(); // Write new format
        }
    }
}

private boolean needsMigration(Object config) {
    // Check for old format markers:
    // - Has <resources> but no <groups>
    // - Version field < current version
    return true; // simplified
}

private void migrateConfig(Object oldConfig) {
    // Move all resources to 'common' group
    ResourceGroup common = new ResourceGroup("common");
    for (LockableResource r : extractOldResources(oldConfig)) {
        common.addResource(r);
    }
    this.groups.put("common", common);
}
```

### Version Field

Add explicit version for future migrations:
```java
private int configVersion = 2; // Bump on each breaking change
```

---

## Implementation Phases

### Phase 1: Name Index (1-2 days) ✅ Safe to implement
- Add `resourcesByName` ConcurrentHashMap
- Update `fromName()` to use index
- Update add/remove operations to maintain index
- No config changes, fully backward compatible
- Write tests

### Phase 2: Resource Pages (1 week)
- Create LockableResourcePage class (Stapler routable)
- Add URL routing `/lockable-resources/<name>/`
- Design detail page layout
- Implement history storage (start simple)
- Add basic statistics

### Phase 3: Statistics Collection (3-4 days)
- Add event tracking on lock/unlock
- Implement stats aggregation
- Add stats API
- Integrate with resource page

### Phase 4: Groups (1-2 weeks) ⚠️ Breaking change
- Design group data model
- Implement group management UI
- Add migration logic
- Update lock step to support `group:` parameter
- Update all existing tests
- Write migration tests
- Update documentation

### Phase 4b: Folder-Scoped Resources ([#186](https://github.com/jenkinsci/lockable-resources-plugin/issues/186)) (1-2 weeks)
- Depends on Phase 4 (Groups)
- Add `cloudbees-folder` as optional dependency
- Implement `LockableResourcesFolderProperty` (extends `AbstractFolderProperty`)
- Implement hierarchy-walk resource resolution in `LockStepExecution`
- Add folder configuration UI (Jelly) for managing folder-scoped groups
- Add `scope` parameter to `LockStep`
- Handle ephemeral resource creation in folder context
- Ensure name uniqueness rules per scope
- Write integration tests with JenkinsRule + folder hierarchy
- Document folder-scoping in `src/doc/examples/`

### Phase 5: Performance Optimizations
- Add benchmarking harness (JMH or integration-level load test)
- Replace Guava with Caffeine
- Add label index
- Add reverse cache index (resource → cache keys) for O(1) `uncacheIfFreeing()`
- Consider read-write lock instead of single mutex
- Profile and optimize `proceedNextContext()` hot loop
- Benchmark at scale — define regression gate for CI

### Phase 6: Node Integration & `lockNode()` Step ⏸️ Low Priority
- Implement `lockNode()` step as syntactic sugar over `lock()`
- Add node-specific environment variables (`LOCKED_NODE`)
- Validate UX with real users
- Evaluate deeper integration (Option B: `nodeName` field on `LockableResource`)
- **Decision:** Low priority. May be implemented in a Groovy shared library
  first. When done as a plugin feature, dependency management works from
  scratch. Revisit when other phases are complete.

### Phase 7: Multi-Instance Synchronization ([#321](https://github.com/jenkinsci/lockable-resources-plugin/issues/321)) ⏸️ Deferred
- Define `LockBackend` interface
- Implement `LocalLockBackend` (current behavior, default)
- Implement `RestMasterLockBackend` (REST API to a master instance)
- Add configuration UI for backend selection and master URL
- Handle failure modes (master down, network partition, stale locks)
- Write integration tests with two Jenkins instances
- **Decision:** Skipped for now. Will be implemented in a later cycle.

### Phase 8: AI Engineering
- Publish APM skills for plugin developers (Microsoft Copilot skill format)
- Publish APM cookbook skills for plugin users (pipeline recipes)
- Maintain `.github/copilot-instructions.md` as living context
- Evaluate AI-assisted resource management (predictive lock scheduling)

---

## Open Questions

### Groups

1. **Should groups have access control?**
   - E.g., only certain users/jobs can lock resources in a group
   - Adds complexity but valuable for multi-tenant scenarios
   - **Decision: Yes.** Access control is needed for groups.

2. **Can a resource belong to multiple groups?**
   - Simpler: No, exactly one group
   - Flexible: Yes, like tags
   - **Decision: Single group.** A resource belongs to exactly one group.

3. **Default group name?**
   - Options: `common`, `default`, `global`, `_default_`
   - Must be valid in pipelines: `group: 'global'`
   - **Decision: `global`.**

4. **How to handle ephemeral resources?**
   - Create in same group as trigger?
   - Always in `common`?
   - Configurable default group?
   - **Decision:** Start with a dedicated group called `ephemeral`.
     Later, allow creating ephemeral resources in any group.

### Resource Pages

5. **History retention policy?**
   - Keep last N events per resource?
   - Age-based: delete events older than X days?
   - Configurable per-resource or global setting?

6. **Statistics computation?**
   - Real-time (computed on page load)?
   - Pre-aggregated (updated on events)?
   - Hybrid (recent real-time, old aggregated)?

**Dashboard enhancements (decided):**
- The main `/lockable-resources/` page is now a dashboard (left: resources,
  right: queue).
- Add a **queue size timeline chart** (last 24 hours) to the dashboard.
- The same chart should also appear at `/lockable-resources/queue/timeLine`
  as a dedicated sub-page.
- The `/lockable-resources/labels` page should include a **label expression
  filter** supporting boolean expressions, e.g. `LabelA && !LabelB && LabelC`.

### Label Expressions

7. **Can we optimize complex expressions?**
   - `(A && B) || !C` requires understanding all labels
   - Partial index possible: if expression references single label, use index
   - Parser to detect simple vs. complex expressions?

### Breaking Changes

8. **How to communicate breaking changes?**
   - Release notes
   - Upgrade guide
   - In-UI warning before upgrade?
   - Separate major version (3.0)?
   - **Decision:** Communicate via Release notes AND Upgrade guide.

### Node Integration

9. **Should `lockNode()` be a separate step or an extension of `lock()`?**
   - Separate step: clearer semantics, easier docs
   - Extension: single entry point, less API surface
   - **Decision: Separate step.**

10. **How to handle offline nodes?**
    - Skip offline nodes when selecting candidates?
    - Wait for node to come online (with timeout)?
    - Make behavior configurable?

11. **Should node labels and resource labels be unified?**
    - Unified: simpler mental model
    - Separate: more flexible, avoids namespace collisions
    - Recommendation: keep separate but allow combining in `lockNode()`

### Multi-Instance Sync

12. **Which backend should be the default?**
    - `LocalLockBackend` (current behavior) — safest default
    - Must be opt-in to avoid breaking existing setups

13. **How to handle master unavailability?**
    - Fail-open (allow lock, risk conflicts)
    - Fail-closed (block, wait for master)
    - Configurable with timeout

14. **Lock lease / TTL?**
    - Stale locks from crashed instances need automatic expiration
    - TTL-based lease with periodic renewal?

### Performance

15. **What is the benchmarking baseline?**
    - Need to establish current lock-cycle latency (p50, p95, p99)
    - Need to measure at 100, 500, 1000 resources with 100+ queue items
    - Should run as part of CI (lightweight variant)

16. **Read-write lock vs single mutex?**
    - Many operations are read-only (status checks, UI rendering)
    - `ReadWriteLock` could allow concurrent reads
    - Risk: harder to reason about, potential subtle bugs

---

## AI Engineering

### Vision

Leverage AI tooling in two directions:

1. **AI for plugin developers** — help contributors understand the codebase,
   write correct code, and follow conventions.
2. **AI for plugin users** — help Jenkins administrators and pipeline authors
   use lockable resources effectively.

### APM Skills for Plugin Developers

Publish reusable skills (Microsoft Copilot APM format) that encode:
- Codebase conventions (from `.github/copilot-instructions.md`)
- Common patterns (locking lifecycle, queue processing, test structure)
- Architecture decisions and rationale
- Debugging guides (synchronization issues, cache behavior)

Example skill topics:
- "How to add a new pipeline step parameter"
- "How the lock queue works"
- "Writing a JenkinsRule integration test for this plugin"
- "How `syncResources` synchronization works"

### APM Cookbook for Plugin Users

Publish recipe-style skills for Jenkins pipeline authors:
- "Lock a resource with timeout" (the #740 pattern)
- "Lock multiple resources atomically"
- "Use inversePrecedence for priority builds"
- "Lock a node exclusively with `lockNode()`" (future)
- "Dynamic resource pool expansion"
- "Scripted vs Declarative lock patterns"

These recipes map directly to `src/doc/examples/` and can be kept in sync.

### Maintaining AI Context

- Keep `.github/copilot-instructions.md` up to date with every PR
- Consider adding `ARCHITECTURE.md` for deeper context
- Tag key design decisions in code comments for AI discoverability
- Evaluate GitHub Copilot Workspace for contributor onboarding

---

## Session Notes (2026-04-14)

### What We Learned

1. **Cache Invalidation Pattern**
   - Current `uncacheIfFreeing()` invalidates cache entries containing freed resources
   - Must iterate all cache entries to find affected ones
   - With 1000+ queue items, this becomes expensive
   - Fixed in #892: only invalidate entries that actually contain the resource

2. **Label Expression Complexity**
   - Labels can be complex boolean expressions: `(ABC && DD) || !HH`
   - Cannot simply index by label - need to evaluate expression
   - Current approach: cache candidates per queueItemId
   - Cache key: queueItemId (Long)
   - Cache invalidated when resource freed

3. **Synchronization Challenges**
   - `syncResources` lock held during many operations
   - `scheduleQueueMaintenance()` must be called OUTSIDE sync block
   - Potential deadlock: plugin lock + Jenkins queue lock

4. **Ephemeral Resources**
   - Resources created dynamically when locked by name but don't exist
   - Marked with `ephemeral = true`
   - Auto-deleted when unlocked
   - New setting `allowEphemeralResources` (default: true)

5. **Stolen Resources**
   - Resource can be "stolen" from build to user
   - Both locked AND reserved
   - Complex state to track

### Issue Triage Summary

From the 2014-2017 cleanup session:

| Category | Issues | Notes |
|----------|--------|-------|
| Timeout feature | #30, #849, #866 | Add `lockTimeOut` label, likely duplicates |
| Matrix builds | #828, #834 | Need clarification, complex use case |
| Inverse precedence | #861, #864 | Feature exists, needs more tests |
| Approved | #844, #847, #858 | Ready for implementation, in Milestone 5 |
| Implemented | #838, #856 | Closed - features already exist |

### Configuration Files

- Main config: `work/org.jenkins.plugins.lockableresources.LockableResourcesManager.xml`
- Async save: coalesced writes with 1000ms delay (configurable)
- BulkChange support for batch updates

### Useful System Properties

```
org.jenkins.plugins.lockableresources.DISABLE_SAVE=true  # Disable saving
org.jenkins.plugins.lockableresources.ASYNC_SAVE=false   # Sync saves only  
org.jenkins.plugins.lockableresources.SAVE_COALESCE_MS=1000  # Coalesce window
org.jenkins.plugins.lockableresources.PRINT_BLOCKED_RESOURCE=2  # Debug output
org.jenkins.plugins.lockableresources.PRINT_QUEUE_INFO=2  # Debug output
```

---

## References

- [Jenkins Computer page](https://github.com/jenkinsci/jenkins/blob/master/core/src/main/java/hudson/model/Computer.java) - Inspiration for resource pages
- [Caffeine Cache](https://github.com/ben-manes/caffeine) - Guava replacement
- [ConcurrentHashMap](https://docs.oracle.com/javase/17/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html) - For indexes

---

## Next Steps

1. [ ] Create GitHub issue for overall refactoring epic
2. [ ] Create sub-issues for each phase
3. [ ] Start with Phase 1 (name index) - low risk, immediate benefit
4. [ ] Benchmark current performance for baseline
5. [ ] Design resource page mockups
