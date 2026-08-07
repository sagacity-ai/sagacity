# Production checklist

Sagacity `0.1.0` sits in the path of real side effects. Work through this before
pointing it at anything that moves money.

## Must do

### Secure the REST endpoints

The approval endpoints are **unauthenticated by default**. Anything that can
reach `/sagacity/**` can approve a wire transfer.

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/sagacity/approve/**", "/sagacity/reject/**",
                         "/sagacity/resume/**").hasRole("APPROVER")
        .requestMatchers("/sagacity/audit/**").hasRole("AUDITOR")
        .requestMatchers("/sagacity/approvals/**").hasRole("APPROVER"));
```

Or turn them off and drive approvals through your own UI:

```yaml
sagacity:
  approval-endpoints-enabled: false
```

### Pass a real approver identity

Sagacity records `{"approver":"..."}` verbatim and does not verify it. A caller
can claim to be anyone. Pass the authenticated principal from your own security
context, never a value the client supplies:

```java
sagacity.approve(sagaId, seq, authentication.getName());
```

Without this, the approver field in your audit trail is decorative.

### Use the Postgres journal

`Sagacity.create()` uses the in-memory journal. It writes no hash chain and
disappears on restart. Confirm at startup that a `DataSource` is present and
`verifyJournal` does not report *"not hash-chained"*.

### Make compensations idempotent

They may be attempted more than once. See
[Compensation](compensation.md#write-compensations-to-be-idempotent).

### Alert on `COMPENSATION_FAILED`

This status means an effect happened and could not be undone. It is the one
outcome that always needs a human.

```java
if (result.status() == SagaStatus.COMPENSATION_FAILED) {
    pager.alert("saga {} left dirty state", result.sagaId());
}
```

### Alert on unmatched `INTENT`

An `INTENT` row with no following `EXECUTED` or `FAILED` means the process died
mid-tool-call. The effect may or may not have happened, and compensation will not
touch it because it never reached `EXECUTED`. Sweep for these:

```sql
SELECT j.saga_id, j.seq, j.tool_name, j.timestamp
FROM side_effect_journal j
WHERE j.phase = 'INTENT'
  AND NOT EXISTS (
      SELECT 1 FROM side_effect_journal l
      WHERE l.saga_id = j.saga_id AND l.seq > j.seq
        AND l.tool_name = j.tool_name
        AND l.phase IN ('EXECUTED','FAILED'))
  AND j.timestamp < now() - interval '10 minutes';
```

## Should do

### Anchor the hash chain

Tamper evidence only binds someone who cannot recompute the chain. Anyone with
database write access can. Periodically export the head hash per saga and store
it somewhere the database operator does not control — object storage with
object-lock, a signed commit, or a notary service.

```java
List<JournalEntry> entries = journal.entries(sagaId);
String head = entries.get(entries.size() - 1).hash();
anchorStore.record(sagaId, head, Instant.now());
```

### Restrict database privileges

The journal is append-only by intent, not by permission. Enforce it:

```sql
REVOKE UPDATE, DELETE ON side_effect_journal FROM sagacity_app;
GRANT INSERT, SELECT ON side_effect_journal TO sagacity_app;
```

This is the single highest-value hardening step, and it is one `GRANT`.

### Set a retention policy

Sagacity never deletes rows. Decide how long you keep them, and remember that
deleting a saga's early rows truncates its chain.

### Use a distinct saga ID per task

Journals are keyed by `saga_id`. Reusing one across unrelated tasks interleaves
their entries and makes compensation walk backward across both.

## Know these limits

| Limit | Consequence |
|---|---|
| No approval expiry | A request pending for weeks is still approvable |
| No policy version in the journal | You cannot prove which rules were in force at decision time |
| No idempotency key passed to tools | Sagacity does not deduplicate at the tool boundary; your tools must |
| No streaming support | Only synchronous `ChatClient` flows are journaled |
| Chain is per saga | No global ordering across sagas, and tail truncation is undetectable |
| Approver identity unverified | Only as trustworthy as the caller you put in front of it |

## Verify your setup

```java
@Test
void journalIsHashChainedInThisEnvironment() {
    sagacity.saga("smoke-test", () -> tools.noop());
    var v = sagacity.verifyJournal("smoke-test");
    assertThat(v.message()).doesNotContain("not hash-chained");
    assertThat(v.valid()).isTrue();
}
```

Run that against staging. It catches the in-memory-journal-by-accident case,
which is silent and total.
