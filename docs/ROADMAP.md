# Sagacity — Roadmap

Estimates assume AI-assisted coding, ~2 weekend blocks/week alongside the
Spring AI contribution track. "Done" = tested + documented, not just working.

## M0 — Walking skeleton ✅ DONE 2026-07-16
- Maven multi-module build (`core`, `spring-ai`, `examples`; the `starter`
  module moved to M1 — it needs the journal + auto-wrap design first).
- `@Compensable`/`@Compensation` annotations, in-memory journal,
  `SagacityToolCallback` decorator, `CompensationRunner`, `Sagacity` facade.
- 10 tests green, incl. the design-critical
  `sagaDetectsToolFailureEvenWhenSpringAiSwallowsIt`.
- **Exit test met:** `PlaceOrderDemo` (Order/Product/Inventory — the canonical
  example domain everywhere) — 3 tools, step 3 throws, steps 1–2 compensate in
  reverse order. This demo is the README GIF.

## M1 — Real journal + real integration (2–3 weekends)
- `SagacityToolCallingManager` decorating Spring AI's `DefaultToolCallingManager`.
- Postgres journal (Spring Data JDBC), hash-chained rows, saga state machine.
- Compensation runner with per-entry outcome journaling; `SUSPECT` state for
  unknown-effect failures.
- **Exit test:** crash test — kill the JVM mid-saga, restart, journal is intact and
  compensation completes (see TESTING.md).

## M2 — Approval gates + audit export (2 weekends)
- `IRREVERSIBLE` tools suspend the saga; REST approve/reject endpoint; approver
  identity journaled.
- Audit export (JSON Lines + chain-verification CLI) mapped to EU AI Act
  Article 12 fields — this mapping table is itself launch-post content.
- **Exit test:** tamper with a journal row in SQL → verifier detects the break.

## M3 — Launch (1–2 weekends + writing time)
- `sagacity-examples`: order-placing agent demo app w/ induced failures, docker-compose.
- README, reference docs, javadoc; publish to Maven Central (io.github.* groupId).
- Launch post: "The SAGA pattern for AI agents" + Article 12 angle.
  Targets: HN, r/java, Spring community, InfoQ pitch. Cite the RAC arXiv paper.
- **Exit criterion:** a stranger can go from `git clone` to compensated failure demo
  in under 10 minutes.

## M4 — Ecosystem (post-launch, driven by feedback)
- DBOS integration (durable compensation runs) — `sagacity-dbos`.
- LangChain4j adapter — `sagacity-langchain4j`.
- Streaming tool-call support.
- MCP tool support (compensations for MCP-server tools declared client-side).
- Open-core candidates (only if traction): audit UI, retention policies, RBAC,
  multi-tenant approval workflows.

## Explicitly deferred
- Python/TS ports; agent-to-agent saga propagation; automatic undo inference.
