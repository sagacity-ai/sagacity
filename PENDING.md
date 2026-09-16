# Sagacity — Pending Features

## M4 Retry (Tool-level)

**What:** Retry transient tool failures before compensating.
**Where:** `sagacity-core` — `@Compensable` annotation + `SagacityToolCallback`
**API:**
```java
@Compensable(
    by = "releaseInventory",
    retries = 3,
    retryOn = { InventoryServiceException.class, SocketTimeoutException.class }
)
```
Global default via `application.yml`:
```yaml
sagacity:
  retry:
    default-max-attempts: 3
    default-backoff-ms: 100
    backoff-multiplier: 2.0
```
**Rules:**
- Retries only fire BEFORE the side effect is journaled as EXECUTED
- Journal sees only final outcome (EXECUTED after N retries, or FAILED if all exhausted)
- Whitelist approach: only retry on explicitly declared exception types
- No exception filter = no retry (fail fast, compensate immediately)

---

## M4 Retry (Cloud Journal)

**What:** Retry journal writes when Sagacity Cloud / D1 is temporarily unavailable.
**Where:** `sagacity-core` — `CloudSideEffectJournal`
**Strategy:** More aggressive than tool retry — losing a journal entry is worse than a
failed tool call. If a side effect ran but wasn't journaled, the audit trail has a gap.
- Retry up to 5 times with exponential backoff (200ms, 400ms, 800ms, 1600ms, 3200ms)
- Circuit breaker: after 3 consecutive failures, open the circuit for 30s before retrying
- If all retries exhausted: throw `CloudJournalException` — abort the saga entirely
  (do NOT silently continue with an incomplete audit trail)
**Note:** Requires idempotency on the server side — server must deduplicate on
(team_id, saga_id, seq) to make retries safe. The UNIQUE constraint in D1 already
handles this — a duplicate insert returns a constraint error, not a double write.
