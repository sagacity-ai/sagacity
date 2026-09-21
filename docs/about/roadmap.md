# Sagacity — Roadmap

"Done" = tested + documented, not just working.

## M0 — Walking skeleton ✅ DONE 2026-07-16
- Maven multi-module build (`core`, `spring-ai`, `examples`; the `starter`
  module moved to M1 — it needs the journal + auto-wrap design first).
- `@Compensable`/`@Compensation` annotations, in-memory journal,
  `SagacityToolCallback` decorator, `CompensationRunner`, `Sagacity` facade.
- 10 tests green, incl. the design-critical
  `sagaDetectsToolFailureEvenWhenSpringAiSwallowsIt`.
- **Exit test met:** `PlaceOrderDemo` — 3 tools, step 3 throws, steps 1–2
  compensate in reverse order.

## M1 — Real journal + security hardening ✅ DONE 2026-08-07
- `PostgresSideEffectJournal`: hash-chained rows, saga state machine.
  Decoration at `ToolCallback` level (not `ToolCallingManager`) — the key
  architectural decision that lets Sagacity detect failures Spring AI swallows.
- Compensation runner with per-entry outcome journaling.
- Security hardening from r/SpringBoot community review:
  - Stale approval vulnerability (approvals now SHA-256 bound to exact payload)
  - Approval gate bypass (pending ≠ approved; requires journaled APPROVED decision)
  - Double compensation on rejection path (compensation ran twice — double refund)
  - Hash check failing open (empty inputHash no longer passes)
  - Concurrent journal data loss (`SELECT FOR UPDATE` on empty set locks nothing;
    8 threads × 10 appends → 10 rows, 70 lost. Fixed with optimistic retry on
    SQLSTATE 23505. A dropped EXECUTED row = an effect never undone.)
  - Hash chain canonicalization (fields joined with `|` allowed collisions;
    now length-prefixed)
  - `@RestController` not a bean (every documented endpoint 404'd in real apps)
- **Exit test met:** tamper a journal row in SQL → verifier detects the break.

## M2 — Approval gates + audit export ✅ DONE 2026-08-07
- `IRREVERSIBLE` tools suspend the saga; REST approve/reject/resume endpoints;
  approver identity journaled.
- Audit export (JSON Lines + chain-verification endpoint) mapped to EU AI Act
  Article 12 fields.
- `PostgresApprovalStore` — durable store selected automatically when a
  `DataSource` is present. In-memory store lost all pending approvals on restart.

## M3 — Spring Boot Starter + Maven Central ✅ DONE 2026-08-07 (v0.1.0)
- Published to Maven Central as `io.github.sumitvairagar:sagacity-*:0.1.0`,
  GPG-signed with sources and javadoc.
- `sagacity-spring-boot-starter`: zero-config auto-wiring, schema init, REST
  endpoints conditional on servlet web app + enabled property.
- Documentation site (MkDocs Material, built with `--strict`). Threat model
  published — states known limitations explicitly.
- CI: `mvn verify` on every push. Build fails if ITs are skipped (not just fail).
  Testcontainers ITs run against real Postgres on every PR.
- 88 unit tests + 18 Testcontainers integration tests, all green.

## M3.5 — Cloud journal + retry + universal JDBC ✅ DONE 2026-09-17 (v0.2.0)
- **`CloudSideEffectJournal`** — writes journal entries to Sagacity Cloud API
  via JDK HttpClient (no Jackson, no external deps). Retry on 5xx/429 with
  exponential backoff. Safe to retry — D1 enforces `UNIQUE(team_id, saga_id, seq)`.
- **Tool-level retry** — `@Compensable(retries=3, retryOn={TransientException.class})`.
  `RetryPolicy` value object. Whitelist semantics: empty `retryOn` = no retry
  (safe default). Backoff: `initialDelayMs * multiplier^(attempt-1)`, capped at 30s.
  Retries are transparent to the journal (no intermediate entries).
- **`SagacityProperties`** — `sagacity.retry.initial-delay-ms`,
  `sagacity.retry.backoff-multiplier`, `sagacity.cloud.api-key`, `sagacity.cloud.base-url`.
- **`JdbcSideEffectJournal`** (replaces `PostgresSideEffectJournal`) — pure
  optimistic concurrency, works on PostgreSQL, MySQL, MariaDB, Oracle, H2, SQLite.
  Detects Oracle at construction for `FETCH FIRST` vs `LIMIT`.
- Testcontainers IT suite runs identical assertions against PostgreSQL and MySQL.
- **167 tests total, all passing.**

## M4 — Ecosystem (post-launch, driven by feedback)
- **Typed compensation methods** — auto-bind original tool parameters and result
  to the compensation method signature (no more manual JSON parsing). Eliminates
  `CompensationContext` string wrangling for the common case.
- **Approval dashboard UI** — the enterprise killer feature. Visual approval
  queue, saga timeline, audit view. React/Vue, hosted on Sagacity Cloud.
- **Authenticated approver identity** — Spring Security integration for approval
  endpoints (currently unauthenticated by default, documented in threat model).
- **Approval expiry and policy versioning** — time-bound approvals, policy
  version recorded in journal for compliance.
- **DBOS integration** — durable compensation runs (`sagacity-dbos`).
- **LangChain4j adapter** — `sagacity-langchain4j`.
- **Streaming tool-call support** — currently synchronous `ChatClient` flows only.
- **MCP tool support** — compensations for MCP-server tools declared client-side.
- **Head-hash anchoring** — so tail truncation and wholesale chain rewriting
  become detectable (currently only edited rows are detected).

## Explicitly deferred
- Python/TS ports; agent-to-agent saga propagation; automatic undo inference.
