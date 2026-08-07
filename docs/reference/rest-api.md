# REST API

Exposed by `sagacity-spring-boot-starter` when a web application context is
present. All paths are under `/sagacity`.

!!! danger "Unauthenticated by default"
    These endpoints approve and execute irreversible actions. Secure them before
    deployment — see the [production checklist](../guides/production-checklist.md).
    Disable entirely with `sagacity.approval-endpoints-enabled=false`.

## `GET /sagacity/approvals`

Every pending approval request.

```json
[
  {
    "sagaId": "transfer-77",
    "journalSeq": 3,
    "toolName": "sendWireTransfer",
    "input": "{\"amount\":\"100\",\"to\":\"alice\"}",
    "inputHash": "9f2c4a...64 hex chars"
  }
]
```

`inputHash` is `SHA-256(input)`, recorded when the gate fired. Execution later
verifies the live payload against it.

## `GET /sagacity/approvals/{sagaId}`

The same, filtered to one saga. Returns `[]` for an unknown saga.

## `POST /sagacity/approve/{sagaId}/{seq}`

Records a human decision. **Does not execute the tool.**

```json
{"approver": "manager@company.com"}
```

Missing `approver` records `"unknown"` rather than failing.

**`200`**

```json
{
  "sagaId": "transfer-77", "journalSeq": 3, "approved": true,
  "approverIdentity": "manager@company.com", "decidedAt": "2026-08-07T09:33:15Z"
}
```

The pending request deliberately remains in the store so `/resume` can still
verify the payload against it.

## `POST /sagacity/reject/{sagaId}/{seq}`

Rejects and compensates every step that already ran. Same body and response
shape, with `"approved": false`. The pending request is dropped and cannot be
resumed afterward.

## `POST /sagacity/resume/{sagaId}/{seq}`

Executes an approved tool, verifying it against what was approved.

```json
{"payload": "{\"amount\":\"100\",\"to\":\"alice\"}"}
```

The `payload` is the tool's input JSON as a **string**, so it needs escaping
inside the request body.

| Status | Meaning |
|---|---|
| `200` | executed |
| `400` | body had no `payload` field |
| `404` | no pending approval for this saga/seq, or the tool is not registered |
| `409` | refused — no approval recorded, or payload no longer matches |

**`200`**

```json
{"sagaId": "transfer-77", "status": "COMPLETED", "result": "\"transfer-ok\""}
```

**`409`**

```json
{
  "sagaId": "transfer-77", "status": "COMPENSATED",
  "reason": "Stale approval rejected: payload changed since approval was granted"
}
```

A `409` is a completed action, not a server error: the tool was not called, the
refusal is journaled, and prior steps have been compensated.

!!! note "Payload matching is byte-exact"
    Reformatted JSON is rejected. Pass through the payload from
    `GET /sagacity/approvals` unmodified.

## `GET /sagacity/audit/{sagaId}`

`Content-Type: application/x-ndjson` — one JSON object per journal entry, in
sequence order. See [Audit and verification](../guides/audit-and-verification.md).

## `GET /sagacity/audit/{sagaId}/verify`

```json
{"valid": true, "entryCount": 8, "breakAtIndex": -1, "message": "all 8 entries verified"}
```

| Field | Meaning |
|---|---|
| `valid` | chain intact |
| `entryCount` | entries checked |
| `breakAtIndex` | zero-based index of the first bad entry, `-1` if none |
| `message` | human-readable summary |

`valid: false` with `breakAtIndex: -1` means the journal is not hash-chained at
all — not that tampering was detected.
