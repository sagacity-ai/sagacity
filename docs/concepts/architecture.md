# Architecture

For contributors, and for anyone deciding whether to trust this in their stack.
[The saga model](saga-model.md) explains *why*; this page explains *how*.

## Modules

```
sagacity-core                 no Spring dependency at all
├── journal/                  SideEffectJournal, JournalEntry, Phase, HashChain
│                             InMemorySideEffectJournal, PostgresSideEffectJournal
├── compensation/             CompensationRunner, CompensationRegistry,
│                             CompensationContext, CompensationReport
├── approval/                 ApprovalStore, ApprovalRequest, ApprovalDecision,
│                             InMemoryApprovalStore, PostgresApprovalStore
├── audit/                    AuditExporter
└── annotation/               @Compensable, @Compensation, Reversibility

sagacity-spring-ai            depends on core + spring-ai-model
├── Sagacity                  the facade — wrap(), saga(), approve/reject/resumeSaga
├── SagacityToolCallback      the decorator that does the journaling
├── CompensationScanner       reflection over @Compensable at wrap() time
├── SagaScope                 thread-bound saga context
└── SagaResult                outcome of a saga run

sagacity-spring-boot-starter  auto-configuration + REST
sagacity-examples             runnable demos, not published
sagacity-coverage             aggregate JaCoCo report, not published
```

The dependency direction is strict and deliberate: `core` knows nothing about
Spring. Its only third-party surface is `javax.sql.DataSource`. That is what
makes a LangChain4j adapter possible later without touching the journal or the
compensation runner.

## The hot path

Everything interesting happens in one method: `SagacityToolCallback.call`.

```
ChatClient
  └─ ToolCallingManager  (Spring AI)
       └─ ToolCallback
            └─ SagacityToolCallback        ← we are here
                 │
                 ├─ no saga on this thread? → delegate straight through
                 │
                 ├─ IRREVERSIBLE tool?
                 │    ├─ journal AWAITING_APPROVAL (holds the input)
                 │    ├─ store ApprovalRequest with SHA-256(input)
                 │    ├─ mark the scope awaiting
                 │    └─ return "[AWAITING_APPROVAL] ..." to the model — tool NOT called
                 │
                 ├─ journal INTENT                          ← before execution
                 ├─ delegate.call(input)
                 ├─ journal EXECUTED (result)
                 └─ on throw: journal FAILED, mark scope failed, rethrow
```

Then, back in `Sagacity.saga(...)`:

```
work.get() threw?              → compensate
scope marked failed?           → compensate      ← the swallowed-failure case
scope awaiting approval?       → return AWAITING_APPROVAL
otherwise                      → return COMPLETED
```

## Why decorate `ToolCallback` and not `ToolCallingManager`

This is the single most important design decision, and it was made by reading
Spring AI's source rather than its docs.

`DefaultToolCallingManager` catches `ToolExecutionException` and hands it to a
`ToolExecutionExceptionProcessor`, which converts it into an error *message* fed
back to the model. The model then usually carries on and may even report success.

A decorator at manager level therefore **never sees a tool fail**. It sees a
successful round trip containing an error string. A saga built on it would report
`COMPLETED` while the third tool silently failed and the first two were left live.

Decorating the callback puts Sagacity *inside* that catch. It sees the raw
exception first, journals `FAILED`, marks the scope, and rethrows so Spring AI's
normal handling is unchanged. The regression test is
`sagaDetectsToolFailureEvenWhenSpringAiSwallowsIt`.

If Spring AI later ships a first-class tool-execution hook, that becomes the
better integration point.

## `SagaScope`: a `ThreadLocal`, and its consequences

`SagaScope` binds the active saga id to the current thread. The wrapped callback
reads it to decide whether to journal at all.

This buys a clean API — no context parameter threaded through user tool
signatures, and tools stay usable outside sagas. It costs the following, and
these are real limits rather than todos:

- **Tools must execute on the thread that opened the saga.** Async or reactive
  tool execution will not see the scope, and those calls are silently not
  journaled. Only synchronous `ChatClient` flows are supported.
- **Nested sagas throw.** `open()` refuses if a saga is already active on the
  thread, rather than silently nesting journals.
- **`close()` runs in a `finally`.** A leaked `ThreadLocal` on a pooled request
  thread would attach the next unrelated request to a finished saga.

If streaming support is ever added, this class is what has to change — probably
to a context propagated through Reactor's `Context` rather than a `ThreadLocal`.

## `CompensationScanner`: fail at startup, not at refund time

`wrap()` reflects over the tool bean, finds `@Compensable`, resolves the method
named by `by`, and registers it in the `CompensationRegistry`. A `by` that names
a nonexistent method throws **during `wrap()`**.

The alternative — resolving lazily at compensation time — means a typo surfaces
during a failure, when a refund needs issuing, which is the worst possible
moment. Startup is the right time to learn your undo does not exist.

`@Compensation` is only a marker; the binding is by name.

## Journal writes: why `INTENT` before execution

```
INTENT    chargeCard  {"amount":"100"}    ← committed before the call
EXECUTED  chargeCard  ch_1M2n3            ← committed after it returns
```

A crash between the two leaves an `INTENT` with no outcome. That is not a
bookkeeping gap — it is the most valuable signal the journal produces, because it
says "an effect may exist whose result nobody knows". Compensation deliberately
does not act on it: a tool that may not have completed should not be blindly
undone. It needs a human.

Compensation only walks `EXECUTED` entries, in reverse `seq` order.

## Concurrency in `PostgresSideEffectJournal`

Appends to one saga must be totally ordered — the hash chain depends on it.

The obvious approach, `SELECT ... FOR UPDATE` on the saga's last row, does not
work: it locks nothing when the saga has no rows yet, so concurrent first-appends
all compute `seq = 1` and all but one die on the primary key. Under READ
COMMITTED the same happens later, because a transaction blocked on the current
last row still computes its sequence from the snapshot it already read.

The fix is a bounded retry on SQLSTATE 23505 that re-reads the tail each attempt.
The reasoning that matters: **a losing append must never be dropped.** `append()`
is called *after* the side effect has run, so a lost `EXECUTED` row is an effect
compensation will never undo. Failing the append loudly is acceptable; discarding
it is not.

This was found by an integration test against real Postgres. H2 in PostgreSQL
mode does not reproduce it.

## Two storage shapes, on purpose

| | `side_effect_journal` | `sagacity_approval_request` |
|---|---|---|
| Mutability | append-only | rows deleted when consumed |
| Purpose | evidence | working state |
| Hash-chained | yes | no |
| Survives restart | yes | yes (with a `DataSource`) |

The approval table is not evidence and does not need to be. What was proposed,
who approved it, and what executed all live in the journal, inside the chain. The
request table only carries the pending payload and its hash so a resume can be
verified.

That is also why `approve()` does **not** remove the request: `resumeSaga` still
needs the hash. Which in turn is why `resumeSaga` checks the *journal* for an
`APPROVED` entry rather than trusting the store — store state looks identical
before and after approval.

## Design principles

**Fail closed on the security path.** Every check in `resumeSaga` — request
exists, approval journaled, payload hash matches — refuses on doubt. An approval
with no recorded hash is rejected rather than trusted, because an approval that
never recorded what it approved cannot be shown to match.

**Record intent before acting.** Applies to the journal and to approvals. The
system should always be able to say what it was about to do, even after dying.

**The undo is domain knowledge, so the developer declares it.** Sagacity does not
try to infer how to reverse an effect. Inferring a refund from a charge is
guesswork, and guessing wrong moves money.

**Partial cleanup beats none.** A failing compensation is journaled and the run
continues to the next one. The saga ends `COMPENSATION_FAILED` and the journal
records exactly what is still dirty.

**Evidence, not prevention.** The hash chain detects edits; it does not stop
them. Claiming more would be dishonest — see the [threat model](threat-model.md).

**Be a decorator, not a framework.** No scheduler, no queue, no state machine
beyond the journal, no lifecycle ownership. Sagacity should be removable by
deleting one `wrap()` call.

## Where to look first

| Question | File |
|---|---|
| How does journaling attach to a tool call? | `SagacityToolCallback.call` |
| How does a saga decide it failed? | `Sagacity.saga` and `SagaScope` |
| What runs on failure? | `CompensationRunner.compensate` |
| How is an approval verified? | `Sagacity.resumeSaga` |
| How is a hash computed? | `HashChain.computeHash` |
| What does auto-configuration decide? | `SagacityAutoConfiguration` |

The tests are the other half of the documentation, particularly
`StaleApprovalTest` (the approval security properties) and
`PostgresSideEffectJournalIT` (the guarantees only real Postgres can demonstrate).
