# Audit export and verification

Every tool call inside a saga is journaled. With a hash-chaining journal, that
record is also tamper-evident: an edit made directly in the database can be
detected after the fact.

## Exporting

```java
String jsonLines = sagacity.exportAuditLog("order-123");
```

```bash
curl localhost:8080/sagacity/audit/order-123
```

One JSON object per line, in sequence order:

```json
{"sagaId":"order-123","seq":1,"toolName":"reserveInventory","phase":"INTENT","input":"{\"sku\":\"SKU-9\"}","payload":"","timestamp":"2026-08-07T09:33:15.686364Z","hash":"dc04d16a..."}
{"sagaId":"order-123","seq":2,"toolName":"reserveInventory","phase":"EXECUTED","input":"{\"sku\":\"SKU-9\"}","payload":"res-8891","timestamp":"2026-08-07T09:33:15.705091Z","hash":"603ec948..."}
```

JSON Lines because an audit export is append-oriented and often large — it
streams, greps, and loads into anything without parsing the whole file.

## Verifying

```java
AuditExporter.VerificationResult v = sagacity.verifyJournal("order-123");
```

```bash
curl localhost:8080/sagacity/audit/order-123/verify
```

```json
{"valid":true,"entryCount":8,"breakAtIndex":-1,"message":"all 8 entries verified"}
```

On a tampered chain, `breakAtIndex` points at the first entry whose recorded hash
does not match its recomputed one:

```json
{"valid":false,"entryCount":8,"breakAtIndex":1,"message":"hash mismatch at seq=2 (entry 1)"}
```

Because each hash covers the previous hash, editing entry 2 also invalidates
every entry after it. The break index tells you where the earliest edit was.

## "Not hash-chained" is not "tampered"

```json
{"valid":false,"entryCount":8,"breakAtIndex":-1,
 "message":"journal is not hash-chained — tamper evidence unavailable (use a hash-chaining journal such as PostgresSideEffectJournal)"}
```

`InMemorySideEffectJournal` writes no hashes, so it can make no integrity claim.
That is reported as its own state, distinct from a detected break —
`breakAtIndex` is `-1` because nothing broke; there was simply never any
evidence. Absence of evidence is not evidence of tampering.

If you see this in an environment that matters, you are running the in-memory
journal by accident. Wire a `DataSource`.

## What the chain does and does not prove

**Detects:** any modification to a journal row after it was written — changed
inputs, changed results, changed timestamps, reordered entries.

**Does not detect:**

- **Truncation of the tail.** Deleting the last N entries leaves a shorter but
  perfectly valid chain. Nothing inside the journal can prove entries once
  existed.
- **Wholesale rewriting.** Anyone with database write access *and* the ability to
  run Sagacity's own hashing can recompute the entire chain from entry 1.

The chain is tamper **evidence**, not tamper **prevention**, and it defends
against edits by someone who did not bother to recompute. To harden it, anchor
the head hash somewhere the database operator does not control — periodically
sign the latest hash, or ship it to append-only storage. See the
[threat model](../concepts/threat-model.md).

## Retention

Sagacity does not delete journal rows and has no retention policy. EU AI Act
Article 12 expects logs retained for a defined period appropriate to purpose —
commonly cited as at least six months for high-risk systems. Whatever you choose,
implement it outside Sagacity, and note that deleting old rows truncates those
sagas' chains.

!!! note "Verify per saga, not globally"
    The chain is per `saga_id`. Each saga starts from the zero hash, so removing
    an entire saga's rows leaves every other saga verifiable. There is no global
    chain across sagas in 0.1.0.

## Field mapping for Article 12

| Article 12 expectation | Journal field |
|---|---|
| Period of each use | `timestamp` on first and last entry of a saga |
| Input data | `input` |
| Result | `payload` on `EXECUTED` |
| Persons involved in verification | `payload` on `APPROVED` / `REJECTED` |
| Identification of the system | `saga_id` plus `tool_name` |

This mapping is offered as a starting point for a conversation with your
compliance function, not as a compliance guarantee. Sagacity records events; it
does not assess whether your system is high-risk or whether your retention
satisfies a regulator.
