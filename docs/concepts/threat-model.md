# Threat model

What Sagacity's audit trail does and does not defend against. Read this before
relying on it for anything consequential.

## Assets

1. **The journal** — the record of what the agent did.
2. **The approval record** — who authorised an irreversible action, and for what.
3. **The side effects themselves** — money moved, data deleted.

## Adversaries and outcomes

### Careless or malicious insider with database write access

**Editing a row:** detected. The chain breaks at that entry and
`verifyJournal` reports the index.

**Deleting trailing rows:** **not detected.** A truncated chain is a valid chain.
Nothing inside the journal proves entries once existed.

**Rewriting the whole chain:** **not detected**, if they can run the same hashing
Sagacity uses — the algorithm is open source.

*Mitigations:* `REVOKE UPDATE, DELETE` on the table, and anchor the head hash
somewhere outside the database's blast radius (object-lock storage, a signed
commit, a notary). Anchoring is what converts "evidence against the careless"
into "evidence against the determined".

### A re-planning model substituting a payload

An approval granted for one payload being used to execute a different one.

**Defended.** The approval records `SHA-256(input)` at request time; execution
recomputes and compares byte-exactly. A mismatch is refused, journaled as
`REJECTED`, and prior steps are compensated. An approval with no recorded hash is
refused rather than trusted.

### Anyone who can reach the REST endpoints

**Not defended.** The endpoints are unauthenticated by default, and the approver
identity is whatever the caller puts in the request body. Sagacity records
`{"approver":"..."}` verbatim without verification.

Anything that can reach `/sagacity/approve` and `/sagacity/resume` can authorise
and execute a wire transfer, and the audit trail will faithfully record the
attacker's chosen name.

*Mitigation:* your own authentication in front of the endpoints, and pass the
authenticated principal — never a client-supplied value. See the
[production checklist](../guides/production-checklist.md).

### An attacker who controls tool inputs

Attempting to forge a journal entry by crafting input that collides with another
entry's hash preimage.

**Defended**, by length-prefixed field encoding. See
[the hash chain](hash-chain.md#why-fields-are-length-prefixed).

### Restart with approvals pending

**Defended when a `DataSource` is configured** — `PostgresApprovalStore` persists
pending requests, including the approved payload hash.

Without one, the in-memory fallback loses them. This is availability rather than
integrity: nothing executes that should not, but in-flight approvals become
unresumable and the operator must restart those sagas.

### Process crash mid-effect

An effect executes but its `EXECUTED` row never commits.

**Partially defended.** The `INTENT` row written before execution survives, so an
intent with no outcome is visible and sweepable. But compensation will not touch
it, because compensation only walks `EXECUTED` entries. This requires a human.

*Mitigation:* alert on unmatched `INTENT` rows older than a few minutes — there
is a query in the [production checklist](../guides/production-checklist.md#alert-on-unmatched-intent).

## Summary

| Threat | Status |
|---|---|
| Journal row edited | Detected |
| Journal tail truncated | **Not detected** — anchor the head hash |
| Chain wholly recomputed | **Not detected** — anchor the head hash |
| Stale/substituted approval payload | Defended |
| Approval with no recorded payload hash | Defended (refused) |
| Forged entry via crafted tool input | Defended |
| Unauthenticated endpoint access | **Not defended** — your auth required |
| Spoofed approver identity | **Not defended** — pass an authenticated principal |
| Crash between execution and journaling | Visible as unmatched `INTENT`, needs a human |

## Not claimed

Sagacity is not a compliance product. It records events in a tamper-evident way;
it does not determine whether your system is high-risk under any regulation,
whether your retention period is adequate, or whether your approvals satisfy an
auditor. Those are conversations with your compliance function, for which the
journal is evidence rather than an answer.
