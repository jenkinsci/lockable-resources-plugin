## Remote Lock API — Shell/curl examples

The Remote Lock REST API lets external applications or non-Jenkins scripts acquire a
lockable resource on a Jenkins controller without a Pipeline step.

Another Jenkins controller does not need any of this: it uses `lock(..., serverId: '…')`, described
under [Remote lockable resources](../../../README.md#remote-lockable-resources) in the README. This
page is for everything that is not a Jenkins controller.

### Prerequisites

| Item | Where to configure |
|---|---|
| **Remote API enabled** | Manage Jenkins → Configure System → Lockable Resources (Server) → *Enable remote API* |
| **Resource exposed** | The resource must carry one of the labels listed in *Expose label(s)* |
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
    FAILED)
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

Remote waiters share one queue with local ones on the server, and both fields mean what they mean
for a local `lock()`:

- `priority` — larger number wins queue position.
- `inversePrecedence` — the request jumps to the front of the queue, so the newest waiter is served
  first. It applies only when `priority` is left at its default; giving both falls back to the
  ordinary priority insert, exactly as a local lock does.

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

Inverse precedence example (newest waiter first):

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
| `lockRequest.inversePrecedence` | bool | no | serve the newest waiter first; ignored when `priority` is also set |
| `lockRequest.resourceSelectStrategy` | string | no | `SEQUENTIAL` (default) or `RANDOM` |
| `lockRequest.variable` | string | no | env var name prefix for the acquired resource names |
| `lockRequest.reason` | string | no | human-readable lock reason |
| `lockRequest.extra` | array | no | additional resources to lock atomically |
| `clientId` | string | no | identifier shown in server dashboard |
| `heartbeatIntervalSeconds` | int | no | must be > 0 when provided. Validated, then **ignored** — the server applies its own stale threshold (see heartbeat below) |

`resource` and `label` are mutually exclusive, and so are `priority` and `inversePrecedence`; the
canonical `lock()` refuses either combination and so does this endpoint. Whether a request with no
target at all is legal follows the server's *Allow empty or null values* setting.

Values that cannot be interpreted are rejected rather than quietly defaulted: a non-numeric
`quantity` or an unknown `timeoutUnit` returns 400 `INVALID_FIELD_VALUE`. Numeric strings (`"2"`)
are still accepted, and JSON `null` counts as absent.

Response `202 Accepted`:
```json
{ "lockId": "lr-abc123", "state": "QUEUED" }
```

Error responses:

| HTTP | errorCode | Meaning |
|---|---|---|
| 400 | `INVALID_JSON` | Body is not valid JSON |
| 400 | `MISSING_LOCK_REQUEST` | No `lockRequest` object in the body |
| 400 | `INVALID_REQUEST` | The request is not a legal `lock()`: no target, `resource` with `label`, `priority` with `inversePrecedence` |
| 400 | `INVALID_FIELD_VALUE` | A field's value cannot be interpreted (non-numeric `quantity`, unknown `timeoutUnit`, …) |
| 400 | `INVALID_EXTRA` | An `extra[i]` entry names neither `resource` nor `label` |
| 400 | `INVALID_HEARTBEAT_INTERVAL` | `heartbeatIntervalSeconds` is not a positive integer |
| 400 | `ACQUIRE_FAILED` | The request was rejected for some other reason |
| 403 | `REMOTE_API_DISABLED` | Remote API not enabled on server |
| 403 | *(no body)* | Authentication failure or missing RemoteUse permission |
| 404 | `UNKNOWN_RESOURCE` | Resource does not exist or is not exposed by exposeLabel |
| 404 | `UNKNOWN_LABEL` | Label does not match any exposed resource |
| 413 | `PAYLOAD_TOO_LARGE` | Request body is over 1 MiB |
| 503 | `ACQUIRES_PAUSED` | The server is not accepting new acquires (maintenance). Retry later — locks already held are unaffected |

`UNKNOWN_RESOURCE` and `UNKNOWN_LABEL` are both returned as a plain 404 whether the target is
absent or merely not exposed, so the API does not reveal what exists on the server.

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
| `STALE` | Held, but the heartbeats stopped — see heartbeat below |

A lock that reaches `SKIPPED` or `FAILED` stays readable for 120 seconds and is then forgotten;
polling afterwards returns 404 `LOCK_NOT_FOUND`.

---

#### `POST /lockable-resources/remote/v1/lease/{lockId}/heartbeat`

Send this while holding the lock. Every 10 seconds matches what the Jenkins client does, and is well
inside the threshold below.

If nothing arrives for 60 seconds the server marks the lock `STALE`. It does **not** release it: a
client that has gone quiet may still be using the resource, so handing it to the next waiter on that
guess is exactly what a lock is for preventing. A stale lease is released by an administrator, from
the lockable resources page.

The threshold is the server's, not the client's — `heartbeatIntervalSeconds` in the acquire request
is validated and then ignored in this version.

Response: `204 No Content` on success, `410 Gone` if the lock is no longer active
(`LOCK_STALE` once it has gone stale, `LOCK_NOT_FOUND` if it is already released or unknown).

---

#### `POST /lockable-resources/remote/v1/lease/{lockId}/release`

Releases the lock. Idempotent — safe to call even if already released.

Response: `204 No Content`.

---

#### `GET /lockable-resources/remote/v1/resources/`

Lists what this server exposes, so a client can discover names and labels instead of having them
configured by hand on both sides.

```bash
curl -s -u "${USER}:${TOKEN}" \
  "${JENKINS}/lockable-resources/remote/v1/resources/"
```

Response `200 OK`:
```json
{
  "acceptNewAcquires": true,
  "resources": [
    { "name": "r1", "labels": ["gpu", "remote-enabled"], "description": "", "state": "FREE" },
    { "name": "r2", "labels": ["gpu", "remote-enabled"], "description": "", "state": "LOCKED",
      "heldByKind": "REMOTE_CLIENT", "heldByClientId": "my-script", "since": 1723526400000 }
  ]
}
```

Only resources carrying one of the server's *expose label(s)* appear. `state` is `FREE`, `QUEUED`,
`LOCKED` or `RESERVED`; when it is held, `heldByKind` says by what — `LOCAL_BUILD`, `REMOTE_CLIENT`
or `ADMIN` — and `since` is an epoch-milliseconds timestamp. The build name, its reason and the
resource note are deliberately not included: which resources exist and whether they are busy is what
a borrower needs, and nothing more is disclosed.

`acceptNewAcquires` mirrors the maintenance switch, so a client can tell a paused server from an
unreachable one before it tries to acquire.

---

### Credentials note

Use **username + API token** (not the account password).
Sending the plain account password triggers Jenkins CSRF crumb checks, which
returns HTTP 403 even with correct credentials.

Create an API token at:
`${JENKINS}/user/${USER}/security/` → **Add new Token**
