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

## Generic JDBC Journal

**What:** A `GenericJdbcSideEffectJournal` that works with any JDBC-compatible database
(MySQL, Oracle, MariaDB, SQLite, H2 in production) using standard SQL92 — no
Postgres-specific syntax.

**Why:** `PostgresSideEffectJournal` uses `SELECT ... FOR UPDATE` and `CHAR(64)` which
are Postgres/H2 specific. MySQL/Oracle users currently have to implement the interface
themselves.

**What changes:**
- Extract SQL dialect into a strategy interface: `JournalDialect`
  - `PostgresDialect` — existing behaviour
  - `GenericJdbcDialect` — standard SQL92, sequence via `MAX(seq) + 1` with retry on
    unique key violation (same pattern as Postgres impl but without `FOR UPDATE`)
- `SagacityAutoConfiguration` auto-detects database type from `DataSource.getMetaData()`
  and picks the right dialect automatically
- Users on MySQL/Oracle/MariaDB get zero-config support just like Postgres users

**Constraint:** The hash chain (SHA-256 linking) is maintained in all dialects —
tamper evidence must not be sacrificed for compatibility.

**Priority:** Medium — blocks enterprise adoption on MySQL/Oracle shops.


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
