# Compensating a failed saga

## Declaring an undo

```java
@Tool(description = "Reserve inventory")
@Compensable(by = "releaseInventory")
public String reserveInventory(String sku, String quantity) {
    return inventory.reserve(sku, Integer.parseInt(quantity));
}

@Compensation
public void releaseInventory(CompensationContext ctx) {
    inventory.release(ctx.result());
}
```

The binding is **by method name**, in the same class. `@Compensation` is a
documentation marker in 0.1.0 — it makes the pairing greppable but is not what
wires it up. The compensation method may take no arguments or a single
`CompensationContext`.

`wrap()` validates these declarations at startup and throws if `by` names a
method that does not exist, so a typo fails on boot rather than during a refund.

## When compensation runs

A saga compensates when **any** of these happen:

- the work supplier throws
- a wrapped tool threw, even if Spring AI swallowed it and fed the error back to
  the model as text
- an approval is rejected
- a resumed tool fails, or its approval fails verification

```java
SagaResult<ChatResponse> result = sagacity.saga("order-123", () -> ...);
```

## Order and scope

Compensations run in **strict reverse order of execution**, over journal entries
in `EXECUTED` phase only:

```
reserveInventory  EXECUTED       ← compensated last
chargeCard        EXECUTED       ← compensated first
dispatchOrder     FAILED         ← never executed, nothing to undo
```

Only `EXECUTED` entries compensate. A tool that threw did not necessarily
complete its effect, so Sagacity does not assume it needs undoing — that failure
is recorded as `FAILED` and left for a human to interpret.

## Reading the report

```java
CompensationReport report = result.report();

for (CompensationReport.Outcome o : report.outcomes()) {
    switch (o.result()) {
        case COMPENSATED         -> log.info("undone: {}", o.toolName());
        case SKIPPED             -> log.warn("no compensation declared: {}", o.toolName());
        case COMPENSATION_FAILED -> alert("DIRTY STATE: {} {}", o.toolName(), o.detail());
    }
}
```

| Outcome | Meaning |
|---|---|
| `COMPENSATED` | the undo ran cleanly |
| `SKIPPED` | no compensation was declared for that tool |
| `COMPENSATION_FAILED` | the undo itself threw — the effect is still live |

`SKIPPED` is not an error, but it is worth auditing. A tool with a real side
effect and no declared compensation is a silent gap in your recovery story.

## A failing compensation does not stop the run

If `refundCharge` throws, Sagacity journals `COMPENSATION_FAILED` and **continues
to the next compensation**. Partial cleanup beats none, and the journal keeps the
evidence of exactly what is still dirty.

The saga's status becomes `COMPENSATION_FAILED` rather than `COMPENSATED`. Treat
that as a page-a-human condition.

## Write compensations to be idempotent

Compensation may be attempted more than once across process restarts or operator
retries. `release()` on an already-released reservation should be a no-op, not an
exception.

```java
@Compensation
public void releaseInventory(CompensationContext ctx) {
    try {
        inventory.release(ctx.result());
    } catch (AlreadyReleasedException ignored) {
        // Already in the desired state — that is success, not failure.
    }
}
```

!!! note "Within a single run, compensation happens once"
    `CompensationRunner` is not itself idempotent — it re-runs every `EXECUTED`
    entry it finds each time it is called. Sagacity calls it exactly once per
    failure path. If you invoke `CompensationRunner` directly, do not call it
    twice for the same saga; you will issue two refunds.

## Things compensation cannot fix

- **Effects observed by someone else.** A sent email can be followed by a
  correction, not unsent. That is what `COMPENSATABLE` means as distinct from
  `REVERSIBLE` — the undo is a new fact, not an erasure.
- **Effects with no undo at all.** Use
  [`IRREVERSIBLE` and an approval gate](approval-gates.md) instead.
- **Effects the journal never recorded.** If the process dies between the tool
  executing and the `EXECUTED` row committing, that effect is invisible to
  compensation. This is the fundamental limit of journal-based compensation and
  the reason the `INTENT` row is written *before* execution — an `INTENT` with no
  matching `EXECUTED` is the signal that something needs human eyes.
