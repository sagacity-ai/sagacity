# Sagacity — Test Strategy

The product's promise is *reliability during failure*, so the test suite IS the
product. Priorities: failure-path coverage > happy-path coverage.

## Layers

### 1. Unit (sagacity-core, no containers)
- Saga state machine: every legal/illegal transition
  (RUNNING → FAILED → COMPENSATING → COMPENSATED / COMPENSATION_FAILED, AWAITING_APPROVAL…).
- Hash chain: append, verify, detect single-bit tamper.
- Compensation ordering: strict reverse order, skip non-executed entries,
  continue-on-compensation-failure policy.

### 2. Integration (Testcontainers Postgres) — 11 tests
- Chain verifies after a real round-trip through Postgres.
- Timestamp precision survives the round-trip (microseconds, not nanoseconds).
- A direct SQL `UPDATE` is detected by chain verification.
- Payloads containing the old `|` delimiter, and unicode, still verify.
- Concurrent appends produce a contiguous, gap-free, verifiable chain.

That last one earned its keep: it found 70 of 80 concurrent appends being
silently dropped, which 48 green unit tests had missed. H2 in PostgreSQL mode
cannot substitute for real Postgres here.

!!! note "Status as of 0.1.0"
    Layers 1 and 2 exist: 75 unit tests and 11 Testcontainers integration tests
    against real Postgres, run on every push. The crash suite below is
    **designed but not yet built** — treat it as the plan, not the state.

### 3. Crash tests (the signature suite, not yet built)
The demo agent runs in a **separate JVM** (forked process). Test harness:
1. Start saga; wait for journal to show step N EXECUTED.
2. `kill -9` the forked JVM (SIGKILL — no shutdown hooks).
3. Assert journal integrity (chain verifies, no torn rows).
4. Restart process; assert recovery: saga resumes or compensates per policy,
   and no side effect is executed twice (idempotency keys checked via stub tools
   that count invocations in Postgres).

Run matrix: kill during tool execution, between journal-INTENT and execution,
during compensation run, while AWAITING_APPROVAL.

### 4. LLM-free agent tests
CI never calls a real LLM. Tool-call sequences are driven by a **scripted
ChatModel stub** (returns predefined assistant messages with tool calls).
This keeps CI deterministic, free, and fast — and doubles as executable
documentation of supported flows.

### 5. Compliance assertions (M2)
- Every journal entry contains the Article 12 field set (actor, action, input
  digest, output digest, timestamp UTC, approval context).
- Export → verify CLI round-trip on a 10k-entry journal.
- Redaction: configured PII fields never appear in exported payloads.

## Tooling
- JUnit 5, AssertJ, Testcontainers, ArchUnit (module dependency rules:
  core must not depend on Spring AI).
- GitHub Actions: unit+integration on every PR; crash suite nightly (slower).
- Mutation testing (PIT) on sagacity-core once M1 stabilizes — the state machine
  and hash chain must survive mutation testing, they're the trust kernel.
