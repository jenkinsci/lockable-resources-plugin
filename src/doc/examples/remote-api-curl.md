## Remote Lock API — Shell/curl examples

The Remote Lock REST API lets external applications or non-Jenkins scripts acquire a
lockable resource on a Jenkins controller without a Pipeline step.

### Prerequisites

| Item | Where to configure |
|---|---|
| **Remote API enabled** | Manage Jenkins → Configure System → Lockable Resources → Enable Remote API |
| **Resource exposed** | The resource must carry one of the labels listed in *Expose Label* |
| **User with API token** | The user must have **Lockable Resources / RemoteUse** permission |
| **API token** | User → Security → API Token |

All examples below use:

```
JENKINS=http://localhost:8080/jenkins   # root URL of the owning controller
USER=lrm                                # Jenkins user with RemoteUse permission
TOKEN=<api-token>                       # API token (NOT account password)
RESOURCE=r1                             # resource name to lock
```

---

### Basic flow: lock → wait → use → release

```bash
#!/usr/bin/env bash
set -euo pipefail

JENKINS="http://localhost:8080/jenkins"
USER="lrm"
TOKEN="<api-token>"
RESOURCE="r1"
CLIENT_ID="my-script-v1"          # arbitrary identifier shown on server dashboard
POLL_INTERVAL=3                    # seconds between status checks
HEARTBEAT_INTERVAL=10              # seconds between heartbeats while lock is held

# ── 1. Acquire ───────────────────────────────────────────────────────────────
ACQUIRE=$(curl -s -u "${USER}:${TOKEN}" \
  -X POST \
  -H "Content-Type: application/json" \
  -d "{
    \"lockRequest\": { \"resource\": \"${RESOURCE}\" },
    \"clientId\": \"${CLIENT_ID}\",
    \"heartbeatIntervalSeconds\": ${HEARTBEAT_INTERVAL}
  }" \
  "${JENKINS}/lockable-resources/remote/v1/acquire/")

echo "Acquire response: ${ACQUIRE}"
LOCK_ID=$(echo "${ACQUIRE}" | grep -o '"lockId":"[^"]*"' | cut -d'"' -f4)

if [ -z "${LOCK_ID}" ]; then
  echo "ERROR: no lockId in response" >&2
  exit 1
fi
echo "Enqueued with lockId=${LOCK_ID}"

# ── 2. Poll until ACQUIRED ───────────────────────────────────────────────────
while true; do
  STATUS=$(curl -s -u "${USER}:${TOKEN}" \
    "${JENKINS}/lockable-resources/remote/v1/acquire/${LOCK_ID}/")

  STATE=$(echo "${STATUS}" | grep -o '"state":"[^"]*"' | cut -d'"' -f4)
  echo "  state=${STATE}"

  case "${STATE}" in
    ACQUIRED) echo "Lock acquired!"; break ;;
    QUEUED)   sleep "${POLL_INTERVAL}" ;;
    SKIPPED)  echo "Resource busy, skipIfLocked=true → skipping"; exit 0 ;;
    FAILED|EXPIRED|CANCELLED|UNKNOWN)
      echo "ERROR: acquire failed with state=${STATE}" >&2
      echo "Response: ${STATUS}" >&2
      exit 1 ;;
  esac
done

# ── 3. Send heartbeats in background ────────────────────────────────────────
heartbeat() {
  while true; do
    sleep "${HEARTBEAT_INTERVAL}"
    curl -s -o /dev/null -u "${USER}:${TOKEN}" \
      -X POST \
      "${JENKINS}/lockable-resources/remote/v1/lease/${LOCK_ID}/heartbeat" \
      || echo "WARN: heartbeat failed (will retry)" >&2
  done
}
heartbeat &
HEARTBEAT_PID=$!

# ── 4. Do your work here ─────────────────────────────────────────────────────
echo ">>> Resource ${RESOURCE} is locked — running critical section"
sleep 30   # replace with actual work

# ── 5. Release ───────────────────────────────────────────────────────────────
kill "${HEARTBEAT_PID}" 2>/dev/null || true

curl -s -o /dev/null -u "${USER}:${TOKEN}" \
  -X POST \
  "${JENKINS}/lockable-resources/remote/v1/lease/${LOCK_ID}/release"

echo "Lock released."
```

---

### Lock by label (any matching resource)

```bash
curl -s -u "${USER}:${TOKEN}" \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "lockRequest": { "label": "gpu", "quantity": 1 },
    "clientId": "my-script"
  }' \
  "${JENKINS}/lockable-resources/remote/v1/acquire/"
```

`quantity: 0` (or omit) means _all_ matching resources; `quantity: N` picks N.

---

### Skip if already locked (non-blocking)

```bash
curl -s -u "${USER}:${TOKEN}" \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "lockRequest": { "resource": "r1", "skipIfLocked": true }
  }' \
  "${JENKINS}/lockable-resources/remote/v1/acquire/"
```

Response state will be `SKIPPED` instead of `QUEUED` if the resource is busy.

---

### Lock with a timeout (fail instead of waiting forever)

```bash
curl -s -u "${USER}:${TOKEN}" \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "lockRequest": {
      "resource": "r1",
      "timeoutForAllocateResource": 5,
      "timeoutUnit": "MINUTES"
    },
    "clientId": "my-script"
  }' \
  "${JENKINS}/lockable-resources/remote/v1/acquire/"
```

If not acquired within 5 minutes the state becomes `FAILED` with `errorCode: LOCK_WAIT_TIMEOUT`.

---

### Queue ordering: `priority` and `inversePrecedence`

Current remote API behavior:

- `priority` is applied. Larger number wins queue position.
- `inversePrecedence` is accepted in the payload, but is not currently applied to remote queue ordering.

For now, use `priority` if you need ordering control for remote waiters.

Priority example:

```bash
curl -s -u "${USER}:${TOKEN}" \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "lockRequest": {
      "label": "gpu",
      "quantity": 1,
      "priority": 50
    },
    "clientId": "batch-high-priority"
  }' \
  "${JENKINS}/lockable-resources/remote/v1/acquire/"
```

Inverse precedence payload example (currently informational for remote queueing):

```bash
curl -s -u "${USER}:${TOKEN}" \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "lockRequest": {
      "label": "gpu",
      "quantity": 1,
      "inversePrecedence": true
    },
    "clientId": "batch-lifo"
  }' \
  "${JENKINS}/lockable-resources/remote/v1/acquire/"
```

---

### API reference

#### `POST /lockable-resources/remote/v1/acquire/`

Request body (JSON):

| Field | Type | Required | Description |
|---|---|---|---|
| `lockRequest.resource` | string | one of resource/label/extra | exact resource name |
| `lockRequest.label` | string | one of resource/label/extra | Jenkins label expression |
| `lockRequest.quantity` | int | no | number of label matches to acquire (0 = all) |
| `lockRequest.skipIfLocked` | bool | no | return SKIPPED instead of queuing |
| `lockRequest.timeoutForAllocateResource` | long | no | max wait time (default: unlimited) |
| `lockRequest.timeoutUnit` | string | no | `MINUTES` (default), `SECONDS`, `HOURS` |
| `lockRequest.priority` | int | no | higher number = higher queue priority |
| `lockRequest.inversePrecedence` | bool | no | accepted field; currently not applied to remote queue ordering |
| `lockRequest.variable` | string | no | env var name for acquired resource name |
| `lockRequest.reason` | string | no | human-readable lock reason |
| `lockRequest.extra` | array | no | additional resources to lock atomically |
| `clientId` | string | no | identifier shown in server dashboard |
| `heartbeatIntervalSeconds` | int | no | must be > 0 when provided |

Response `202 Accepted`:
```json
{ "lockId": "lr-abc123", "state": "QUEUED" }
```

Error responses:

| HTTP | errorCode | Meaning |
|---|---|---|
| 400 | `MISSING_TARGET` | No resource/label/extra in request |
| 403 | `REMOTE_API_DISABLED` | Remote API not enabled on server |
| 403 | *(no body)* | Authentication failure or missing RemoteUse permission |
| 404 | `UNKNOWN_RESOURCE` | Resource does not exist or is not exposed by exposeLabel |
| 404 | `UNKNOWN_LABEL` | Label does not match any exposed resource |

---

#### `GET /lockable-resources/remote/v1/acquire/{lockId}/`

Response `200 OK`:
```json
{
  "lockId": "lr-abc123",
  "state": "ACQUIRED",
  "lockEnvVars": { "RESOURCE": "r1", "RESOURCE0": "r1" }
}
```

States:

| State | Meaning |
|---|---|
| `QUEUED` | Waiting for resource to become free |
| `ACQUIRED` | Lock held — safe to do work and send heartbeats |
| `SKIPPED` | `skipIfLocked` was true and resource was busy |
| `FAILED` | Acquire rejected or timed out (see `errorCode`) |
| `EXPIRED` | Allocation timeout elapsed |

---

#### `POST /lockable-resources/remote/v1/lease/{lockId}/heartbeat`

Must be sent every `heartbeatIntervalSeconds` while holding the lock.
If missed too many times the server marks the lock as `STALE` and an admin can force-release it.

Response: `204 No Content` on success, `410 Gone` if lock is not active.

---

#### `POST /lockable-resources/remote/v1/lease/{lockId}/release`

Releases the lock. Idempotent — safe to call even if already released.

Response: `204 No Content`.

---

### Credentials note

Use **username + API token** (not the account password).
Sending the plain account password triggers Jenkins CSRF crumb checks, which
returns HTTP 403 even with correct credentials.

Create an API token at:
`${JENKINS}/user/${USER}/security/` → **Add new Token**
