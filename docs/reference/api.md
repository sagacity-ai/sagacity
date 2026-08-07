# Sagacity API

The facade in `dev.sagacity.springai.Sagacity`.

## Construction

```java
Sagacity.create();                                  // in-memory journal + approval store
Sagacity.create(journal);                           // custom journal
Sagacity.create(journal, approvalStore);            // both
```

With the Spring Boot starter, a `Sagacity` bean is auto-configured — inject it
rather than constructing one.

## `wrap(Object... toolBeans)`

```java
ToolCallback[] tools = sagacity.wrap(new OrderTools(), new PaymentTools());
```

Builds Spring AI callbacks from `@Tool` methods, registers every `@Compensable`
declaration (throwing at startup if one is malformed), records the raw callbacks
so approved tools can be resumed later, and returns callbacks that journal.

Outside a saga scope the returned callbacks pass straight through to the
delegate, so the same beans stay usable in non-saga flows.

## `saga(String sagaId, Supplier<T> work)`

```java
SagaResult<ChatResponse> result = sagacity.saga("order-123", () ->
        chatClient.prompt().user("...").toolCallbacks(tools).call().chatResponse());
```

Runs `work` in a saga scope. Compensates if the supplier throws, if any wrapped
tool failed — including when Spring AI swallowed the failure and fed it back to
the model as text — and returns `AWAITING_APPROVAL` if an irreversible tool
suspended the run. A `Runnable` overload exists for work returning nothing.

## `approve` / `reject`

```java
ApprovalDecision d = sagacity.approve(sagaId, journalSeq, "manager@company.com");
ApprovalDecision d = sagacity.reject(sagaId, journalSeq, "manager@company.com");
```

`approve` journals the decision and **does not execute** — call `resumeSaga`.
It deliberately leaves the pending request in the store so the payload can still
be verified. `reject` journals, drops the request, and compensates prior steps.

## `resumeSaga`

```java
SagaResult<String> r = sagacity.resumeSaga(sagaId, journalSeq, livePayload);
SagaResult<String> r = sagacity.resumeSaga(sagaId, journalSeq, livePayload, rawCallback);
```

Executes an approved tool after verifying three things, failing closed on each:

1. a pending approval request exists — otherwise `IllegalStateException`
2. an `APPROVED` decision is journaled for it — otherwise refused and compensated
3. the live payload's SHA-256 matches the approved one — otherwise refused and compensated

The three-argument form looks the tool up among callbacks registered by `wrap()`.
The four-argument form takes the callback explicitly — pass the **unwrapped**
delegate, or the approval gate fires again.

## Inspection

```java
List<ApprovalRequest> pending = sagacity.pendingApprovals();
List<ApprovalRequest> pending = sagacity.pendingApprovals(sagaId);
String jsonLines            = sagacity.exportAuditLog(sagaId);
VerificationResult v        = sagacity.verifyJournal(sagaId);
SideEffectJournal journal   = sagacity.journal();
ApprovalStore store         = sagacity.approvalStore();
```

## `SagaResult<T>`

| Accessor | Meaning |
|---|---|
| `status()` | `COMPLETED`, `COMPENSATED`, `COMPENSATION_FAILED`, `AWAITING_APPROVAL` |
| `value()` | The work's return value — only on `COMPLETED` |
| `report()` | Per-tool compensation outcomes — `null` unless something compensated |
| `failure()` | The originating `Throwable` |
| `awaitingToolName()` | Tool that triggered the gate — only on `AWAITING_APPROVAL` |
| `sagaId()` | The saga id |
