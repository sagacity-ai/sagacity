# Sagacity — Specification (v0.1 draft)

**One-liner:** Declarative compensation (SAGA pattern) for AI agent tool calls,
with a tamper-evident audit trail. Plugs into Spring AI.

## 1. Problem

AI agents perform multi-step tasks whose steps have real-world side effects
(refund issued, ticket created, email sent). Two failure modes are unhandled by
every current JVM framework:

1. **Logical failure mid-task** — step 4 of 6 returns a hard "no". Steps 1–3's side
   effects are live in production and nothing undoes them. Frameworks retry;
   none compensates.
2. **No evidence** — when an agent did something wrong, there is no
   compliance-grade record of what it did, in what order, on whose approval.
   EU AI Act Article 12 (enforceable for high-risk systems from 2026-08-02)
   mandates exactly this record: tamper-evident, retained 6–24 months.

Durability (resume after crash) is solved by DBOS/Temporal/Restate.
**Compensation and evidence are not solved by anyone.** That's Sagacity.

## 2. Goals / non-goals

**Goals (v0.1):**
- Declare a compensating action per tool with one annotation.
- Journal every tool call (append-only, Postgres) before/after execution.
- On saga failure or abort, execute compensations in reverse order.
- Classify reversibility: `REVERSIBLE`, `COMPENSATABLE` (imperfect undo, e.g. correction
  email), `IRREVERSIBLE` (requires human approval gate *before* execution).
- Tamper-evident audit trail: hash-chained journal entries, exportable.

**Non-goals (v0.1):**
- Not a workflow engine or agent framework (we plug into Spring AI, later LangChain4j).
- No distributed transactions/XA, no exactly-once guarantees across systems.
- No automatic inference of undo logic — the developer declares it (that knowledge
  is domain-specific by nature; see decision log).
- No streaming tool-call support in v0.1 (sync `ChatClient` flows first).

## 3. Core concepts

| Concept | Meaning |
|---|---|
| **Saga** | One agent task's transactional scope (e.g. "place order 123"). Has an ID, a status, and a journal. |
| **Compensable tool** | A Spring AI `@Tool` with a declared compensation method. |
| **Side-effect journal** | Append-only Postgres table: one entry per tool invocation (INTENT → EXECUTED / FAILED → COMPENSATED), hash-chained. |
| **Approval gate** | A tool marked `IRREVERSIBLE` suspends the saga until a human approves (or rejects) via API. |
| **Compensation run** | On failure/abort: walk the journal backwards, invoke each compensation, journal each outcome. |

## 4. API sketch (target developer experience)

The canonical example everywhere (docs, tests, demos) is the Order / Product /
Inventory domain: an agent places an order in three steps and the last one fails.

```java
@Component
class OrderTools {

    @Tool(description = "Reserve inventory for a product")
    @Compensable(by = "releaseInventory")                   // ← Sagacity
    public String reserveInventory(String productId, int quantity) { ... }

    @Compensation
    public void releaseInventory(CompensationContext context) { ... } // sees the original result

    @Tool(description = "Create the customer order")
    @Compensable(by = "cancelOrder")
    public String createOrder(String productId, int quantity) { ... }

    @Compensation
    public void cancelOrder(CompensationContext context) { ... }

    @Tool(description = "Email the customer an order confirmation")
    @Compensable(reversibility = IRREVERSIBLE)              // ← can't unsend; human gate (M2)
    public String sendConfirmation(String orderId) { ... }
}
```

```java
// Wrapping an agent task in a saga scope:
SagaResult<ChatResponse> result = sagacity.saga("place-order-123",
    () -> chatClient.prompt()
        .user("Place an order for 2 units of product p-1")
        .toolCallbacks(orderTools)
        .call().chatResponse());
// on failure → compensations run in reverse, journal records everything
```

## 5. Architecture

```
ChatClient
   └─ ToolCallingManager
        └─ ToolCallback  ← Sagacity decorates HERE (SagacityToolCallback)
             │  1. journal INTENT (hash-chained row, Postgres)
             │  2. if IRREVERSIBLE → suspend, await approval (M2)
             │  3. execute real tool via delegate
             │  4. journal EXECUTED (result snapshot) / FAILED + mark saga failed
             └─ on saga failure → CompensationRunner walks journal in reverse
```

- **Integration point (decided in M0, revising the original plan):** decorate
  `org.springframework.ai.tool.ToolCallback`, NOT `ToolCallingManager`. Reason,
  found by reading `DefaultToolCallingManager` source: the manager catches
  `ToolExecutionException` and converts it to an error message for the model
  (via `ToolExecutionExceptionProcessor`), so a manager-level decorator never
  observes raw failures. The callback wrapper sits inside that catch, sees the
  exception first, journals FAILED, marks the saga scope failed, and rethrows —
  Spring AI's normal error handling still applies, and the saga detects failure
  even when it's swallowed (covered by test
  `sagaDetectsToolFailureEvenWhenSpringAiSwallowsIt`). Spring AI issue #6435
  (tool execution callback API) may later give us a first-class hook.
  A Spring Boot starter (`sagacity-spring-boot-starter`, M1) will auto-wrap
  `ToolCallback` beans.
- **Persistence:** Spring Data JDBC + Postgres. Two tables: `saga_instance`,
  `side_effect_journal`. Journal is append-only; each row carries
  `sha256(prev_row_hash || row_payload)` → tamper-evident chain.
- **Approval gates:** saga suspends (status `AWAITING_APPROVAL`); resume via
  `sagacity.approve(sagaId, entryId, approverIdentity)` — REST endpoint provided
  by the starter, approver identity recorded in the journal.
- **DBOS (M4, optional):** journal + compensation runner become DBOS steps so the
  compensation run itself survives crashes. Not a v0.1 dependency — plain Postgres
  keeps the entry barrier at zero.

## 6. Module layout (Maven, Java 17+, Spring Boot 3.x baseline)

```
sagacity-core                  # journal, hash chain, saga state machine, compensation runner (no Spring AI dep)
sagacity-spring-ai             # ToolCallingManager decorator, @Compensable/@Compensation processing
sagacity-spring-boot-starter   # auto-config, approval REST endpoint, schema init
sagacity-examples              # runnable demo: order-placing agent w/ induced failure
```

## 7. Open questions (resolve during M1)

1. Result snapshots in the journal: full JSON vs. schema-limited? (PII/size vs. audit completeness — likely configurable redaction.)
2. Compensation for *partially applied* tools (tool crashed mid-execution, effect unknown) — probably a `SUSPECT` journal state requiring human resolution.
3. How the LLM should be told a compensation happened (feed compensation summary back into the conversation?) — v0.2 question.
4. Saga scope for multi-turn conversations (one saga per turn vs. per conversation).
