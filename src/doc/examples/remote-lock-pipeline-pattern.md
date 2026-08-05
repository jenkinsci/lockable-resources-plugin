# Remote lock from Jenkins Pipeline (local gate + remote lock)

This pattern is useful when your Jenkins controller acts as a client of a remote lock server.

It uses two locks:

1. A local lock (gate) to serialize requests on the client controller.
2. A remote lock (`serverId`) as the authoritative lock on the remote server.

Always acquire in this order: **local first, remote second**.

Always release in reverse order: remote lock exits first, then local gate exits.

---

## Why use a local gate

- Avoids stampedes from many local builds to the same remote selector.
- Gives local queue visibility on the client controller.
- Lets you apply client-side pause/fail-fast policy when remote server is unreachable.

---

## Static remote resource

Create the same local gate resource once on the client controller (for example, `remote-server-1::resourceA`).

```groovy
String serverId = 'remote-server-1'
String remoteResource = 'resourceA'
String localGate = "${serverId}::${remoteResource}"

lock(resource: localGate) {
  lock(resource: remoteResource, serverId: serverId) {
    // critical section
  }
}
```

---

## Dynamic remote resource name

Use a namespaced local gate derived from `serverId` and the chosen remote name.

```groovy
String serverId = 'remote-server-1'
String remoteResource = "resourceB-${env.BUILD_NUMBER}"
String localGate = "${serverId}::${remoteResource}"

// remoteResource must exist (and be exposed) on the remote server
lock(resource: localGate) {
  lock(resource: remoteResource, serverId: serverId) {
    // critical section
  }
}
```

---

## Dynamic capacity from remote label (recommended for pools)

For pooled resources, prefer label + quantity remotely instead of inventing many names.

```groovy
String serverId = 'remote-server-1'
String label = 'labelA'
int quantity = 10
String localGate = "${serverId}::label:${label}::qty:${quantity}"

lock(resource: localGate) {
  lock(label: label, quantity: quantity, serverId: serverId) {
    // critical section using any 10 matching resources from remote server
  }
}
```

---

## Environment variables for remote allocations

Set `variable` in the remote `lock(...)` step to receive allocated resource names and properties
in the pipeline environment.

For `variable: 'PLC'` and two allocated resources, the block receives:

- `PLC` = comma-separated list of allocated resource names
- `PLC0`, `PLC1`, ... = indexed allocated resource names
- `PLC0_<property>`, `PLC1_<property>`, ... = indexed resource properties (for example IP values)
- `PLC_SERVER_ID` = remote serverId that granted the lock
- `PLC_LOCK_ID` = remote lockId

Example:

```groovy
lock(label: 'plc', quantity: 2, serverId: 'remote-server-1', variable: 'PLC') {
  echo "Allocated PLCs: ${env.PLC}"
  echo "Remote server: ${env.PLC_SERVER_ID}"
  echo "Lock id: ${env.PLC_LOCK_ID}"

  // Example property access when each PLC has property 'ip'
  echo "PLC #0 ip: ${env.PLC0_ip}"
  echo "PLC #1 ip: ${env.PLC1_ip}"
}
```

---

## Safety rules

- Keep one global ordering rule in all jobs: local gate -> remote lock.
- Namespace local gate names with `serverId` to avoid collisions.
- Do not treat local gate acquisition as remote ownership.
- Add timeout/retry policy around the outer flow if needed.

## Single source of truth

The remote server is the single source of truth for concrete resource ownership and selection.

Local gate state only serializes demand on the client side. It does **not** mean a concrete remote
resource is already assigned.

This is especially important for label-based requests (`lock(label: ..., quantity: ...)`):

- Local queue order controls when a request is sent to the remote server.
- Remote server state decides *which* concrete resources are granted.
- The final allocation may differ from assumptions made on the client side while waiting.

In short: use local gate order for fairness on the client, but trust remote allocation results as
authoritative.
