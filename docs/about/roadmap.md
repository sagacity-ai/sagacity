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

## M4 — Workflow engine ✅ DONE 2026-09-22 (v0.3.0)

Sagacity graduates from a tool-call interceptor to a full workflow engine. The framing
shifts from "SAGA pattern for Spring AI" to **"the reliability layer for Spring AI agents"**.

- **`sagacity-workflows` module** — `@Workflow`, `@Stage`, `@Gate`, `@Check` annotations
- **`WorkflowRuntime`** — executes stages in declared order, chains stage outputs as
  inputs to the next stage, compensates completed stages in reverse order on failure
- **`@Gate(approvalRequired = true)`** — pauses the workflow for human approval before
  executing a stage. Workflow transitions to `PAUSED_AT_GATE`. REST endpoint ships out
  of the box: `POST /sagacity/workflows/{runId}/gates/{stageName}/approve|reject`
- **`@Check`** — pre-flight checks that block a stage before it runs. Implement
  `StageCheck` as a Spring bean: budget enforcement, Jev risk scoring, precondition validation
- **Startup topology validation** — duplicate stage orders, `@Compensable(by="x")` with
  no matching `@Compensation` method crash the application at startup, not at runtime
- **`WorkflowHandle`** — async execution with `awaitCompletion()`, status polling,
  failure reason retrieval
- **`WorkflowStatus`** — `RUNNING → PAUSED_AT_GATE → COMPENSATING → COMPLETED/FAILED`
- **`GET /sagacity/workflows`**, **`GET /sagacity/workflows/{runId}`** — list and inspect runs
- Inspired by Atomic's verifiable agent runtime — gate-as-first-class-state, topology
  validation at startup, graceful degradation patterns
- **202 tests total across the full repo, all passing.**

## Free vs Paid — feature tier decisions

This table is the product authority for what ships in the open-source library vs what
requires Sagacity Cloud. It is locked in here so future development decisions are
consistent.

### Free forever (open-source library)

| Feature | Status | Notes |
|---|---|---|
| `@Compensable` / `@Compensation` annotations | ✅ shipped | |
| Reverse-order automatic compensation | ✅ shipped | |
| `@Workflow` / `@Stage` / `@Gate` / `@Check` | ✅ shipped v0.3.0 | |
| Stage output chaining | ✅ shipped v0.3.0 | |
| Human approval gates (REST endpoints) | ✅ shipped | No auth — dev/trusted-network use |
| SHA-256 tamper-evident hash chain journal | ✅ shipped | |
| Universal JDBC journal (Postgres, MySQL, H2, etc.) | ✅ shipped | |
| Hash chain verification endpoint | ✅ shipped | |
| Audit export (JSON Lines) | ✅ shipped | |
| Startup topology validation | ✅ shipped v0.3.0 | |
| Tool-level retry with exponential backoff | ✅ shipped | |
| **Embedded UI** — workflow run list + stage timeline | 📋 M5 | Zero-config, ships in starter |
| **Embedded UI** — approve/reject gates from browser | 📋 M5 | No login required, local/trusted use |
| **Embedded UI** — audit trail viewer with hash status | 📋 M5 | |
| **Embedded UI** — saga approval queue | 📋 M5 | |
| JDBC-backed durable workflow state | 📋 v0.4 | In-memory only in v0.3 |
| LangChain4j adapter | 📋 future | |

### Paid (Sagacity Cloud)

| Feature | Rationale |
|---|---|
| Multi-user access with named approver identity | Embedded UI has no auth — Cloud adds login + who-approved-what |
| RBAC — role-based access control | Enterprises require it; never give this away |
| SSO / SAML integration | Every enterprise security policy requires SSO |
| Hosted journal (off your database) | `CloudSideEffectJournal` already exists; retention SLA requires Cloud |
| Audit export to PDF / CSV for regulators | Compliance officers need formatted exports, not raw JSON Lines |
| Cross-deployment workflow history | Embedded UI loses in-memory state on restart |
| Alerting — Slack/email when gate waits >1h, compensation fails | Operational feature for production teams |
| Search across sagas and workflow runs | Query and filter across all runs, not just current JVM |
| Compliance reports per time period | EU AI Act Article 12 formatted reports |
| Data retention SLA | Configurable retention with guarantee |

**The line:** the embedded UI handles everything a single developer or small trusted team needs in development and staging. Cloud is what a production enterprise team needs when multiple people need access, auth, retention, and regulatory reporting.

## M5 — Embedded UI + JDBC durable state (v0.4.0 target)
- **Embedded UI** — zero-config dashboard served at `/sagacity/ui` by the Spring Boot starter.
  No deployment, no separate process, no login required. Ships as static HTML in the JAR.
  - Workflow run list: status badges, stage progress, started/completed timestamps
  - Stage timeline per run: which completed, which failed, which is waiting at a gate
  - Approve / Reject pending gates from the browser — no curl, no Postman
  - Saga approval queue: pending IRREVERSIBLE tool approvals with payload preview
  - Audit trail viewer: journal entries per saga with inline hash verification status
- **JDBC-backed durable workflow state** — replace in-memory `WorkflowRun` store with a
  JDBC table. Workflow state survives JVM restarts. Same DataSource as the journal.
- **Typed compensation methods** — auto-bind original tool parameters and result
  to the compensation method signature. Eliminates `CompensationContext` string wrangling.
- **Approval expiry** — time-bound gates; workflow fails automatically if gate not approved within N seconds.
- **LangChain4j adapter** — `sagacity-langchain4j`.
- **Streaming tool-call support** — currently synchronous `ChatClient` flows only.
- **MCP tool support** — compensations for MCP-server tools declared client-side.
- **Head-hash anchoring** — detect tail truncation and wholesale chain rewriting.

## Explicitly deferred
- Python/TS ports; agent-to-agent saga propagation; automatic undo inference.
- SSO/SAML, RBAC, multi-user approval workflows → Sagacity Cloud only.
