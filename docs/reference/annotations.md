# Annotations

## `@Compensable`

Marks a `@Tool` method as having a declared undo, or as requiring human approval.

```java
@Retention(RUNTIME) @Target(METHOD)
public @interface Compensable {
    String by() default "";
    Reversibility reversibility() default Reversibility.COMPENSATABLE;
}
```

| Attribute | Default | Meaning |
|---|---|---|
| `by` | `""` | Name of the compensation method in the same class. Required unless `reversibility` is `IRREVERSIBLE`. |
| `reversibility` | `COMPENSATABLE` | How undoable the effect is. |

```java
@Tool(description = "Reserve inventory")
@Compensable(by = "releaseInventory")
public String reserveInventory(String sku) { ... }

@Tool(description = "Send a wire transfer")
@Compensable(reversibility = Reversibility.IRREVERSIBLE)
public String sendWireTransfer(String amount, String to) { ... }
```

`wrap()` validates every declaration at startup and throws if `by` names a method
that does not exist — a typo fails on boot, not during a refund.

## `@Compensation`

```java
@Retention(RUNTIME) @Target(METHOD)
public @interface Compensation { }
```

An optional documentation marker on the method named by `Compensable.by()`. In
0.1.0 the binding is purely by method name; this annotation makes the pairing
greppable but does not affect wiring. Omitting it changes nothing.

The method may take **no arguments** or a **single `CompensationContext`**.

## `Reversibility`

| Value | Meaning | Runtime behaviour |
|---|---|---|
| `REVERSIBLE` | Perfect undo exists — the effect can be erased. | Compensates on failure. |
| `COMPENSATABLE` *(default)* | Imperfect undo — a correction, not an erasure. A refund, a follow-up email. | Compensates on failure. |
| `IRREVERSIBLE` | No undo exists. | **Suspends the saga for human approval before executing.** |

The distinction between `REVERSIBLE` and `COMPENSATABLE` is documentation in
0.1.0 — both compensate identically. It is recorded because it is the kind of
thing a reviewer needs to know and nobody remembers a year later.

## `CompensationContext`

Passed to a compensation method so it knows what to undo.

| Accessor | Holds |
|---|---|
| `sagaId()` | The saga the effect belonged to. |
| `toolName()` | The tool that produced it. |
| `input()` | Exact JSON arguments the tool was called with. |
| `result()` | What the tool returned — usually the handle needed to undo it. |

```java
@Compensation
public void releaseInventory(CompensationContext ctx) {
    inventory.release(ctx.result());   // "res-8891"
}
```

A tool that returns `"ok"` gives its compensation nothing to target. Return the
identifier.

---

## Workflow annotations

These annotations are provided by `sagacity-workflows`. Add the module to your
dependencies to use them.

## `@Workflow`

Marks a class as a workflow definition. The class must be a Spring bean.

```java
@Workflow("refund-request")
@Component
public class RefundWorkflow { ... }
```

| Attribute | Default | Meaning |
|---|---|---|
| `value` | *(required)* | Logical name — unique in the application context. Used in audit trail, REST endpoints, and `WorkflowHandle`. |
| `description` | `""` | Human-readable description shown in the approval UI. |

## `@Stage`

Marks a method as an ordered workflow stage. Stages execute in ascending `order`.
Each stage receives the previous stage's return value as its first parameter
if the types are compatible — this is stage output chaining.

```java
@Stage(order = 1)
@Compensable(by = "cancelReservation")
public Reservation reserveInventory(String orderId) { ... }

@Stage(order = 2)
public void chargeCard(Reservation reservation) {
    // 'reservation' injected from stage 1's return value
}
```

| Attribute | Default | Meaning |
|---|---|---|
| `order` | *(required)* | Execution order. Must be unique within the workflow. Gaps allowed. |
| `name` | method name | Stage name in audit trail and approval UI. |

## `@Gate`

Declares a human approval gate on a `@Stage` method. When `approvalRequired = true`,
the workflow pauses before the stage executes and waits for explicit approval.

```java
@Stage(order = 3)
@Gate(approvalRequired = true, reason = "Wire transfer cannot be undone")
public void sendWireTransfer(PaymentDetails payment) { ... }
```

Approve or reject via the REST endpoint or `WorkflowRuntime`:

```bash
# Approve
POST /sagacity/workflows/{runId}/gates/sendWireTransfer/approve

# Reject
POST /sagacity/workflows/{runId}/gates/sendWireTransfer/reject
{"reason": "amount exceeds limit"}
```

| Attribute | Default | Meaning |
|---|---|---|
| `approvalRequired` | `false` | When `true`, workflow pauses and waits. |
| `timeoutSeconds` | `0` (no timeout) | Seconds to wait before the gate times out and the workflow fails. |
| `reason` | `""` | Shown to the approver in the UI and audit trail. |

## `@Check`

Declares pre-flight checks that must pass before a stage executes. Checks run
synchronously before the stage. If any check fails, the workflow fails and
compensates — the stage itself never executes.

```java
@Stage(order = 2)
@Check(BudgetCheck.class)
@Compensable(by = "cancelCharge")
public ChargeReceipt chargeCard(OrderDetails order) { ... }
```

Implement `StageCheck` as a Spring bean:

```java
@Component
public class BudgetCheck implements StageCheck {
    public CheckResult check(StageCheckContext ctx) {
        if (estimatedCost(ctx) > budget.remaining()) {
            return CheckResult.fail("budget exceeded");
        }
        return CheckResult.pass();
    }
}
```

`@Check` takes one or more `StageCheck` classes. All checks run in declaration
order — the first failure blocks the stage.
