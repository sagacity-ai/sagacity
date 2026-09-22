# Verifiable workflows

`sagacity-workflows` gives Spring AI agents Atomic-style declarative workflows:
ordered stages, human approval gates, pre-flight checks, and automatic compensation
when anything fails — all in plain Java annotations.

## Add the dependency

```xml
<dependency>
    <groupId>io.github.sumitvairagar</groupId>
    <artifactId>sagacity-workflows</artifactId>
    <version>0.3.0</version>
</dependency>
```

Requires `sagacity-spring-boot-starter` (provides the `SideEffectJournal`).

---

## Minimal example

```java
@Workflow("refund-request")
@Component
public class RefundWorkflow {

    @Stage(order = 1)
    @Compensable(by = "cancelRefund")
    public RefundConfirmation issueRefund(String orderId) {
        return payments.refund(orderId);
    }

    @Stage(order = 2)
    @Gate(approvalRequired = true, reason = "Email cannot be unsent")
    public void notifyCustomer(RefundConfirmation refund) {
        email.send(refund.customerId(), "Your refund is on its way");
    }

    @Compensation
    public void cancelRefund(CompensationContext ctx) {
        payments.reverse(ctx.result());
    }
}
```

Run it:

```java
@Service
public class OrderService {

    private final WorkflowRuntime runtime;
    private final RefundWorkflow refundWorkflow;

    public void processRefund(String orderId) throws InterruptedException {
        WorkflowHandle handle = runtime.runAsync(refundWorkflow, orderId);
        // Workflow pauses at the @Gate until someone approves
        handle.awaitCompletion(60, TimeUnit.MINUTES);

        if (handle.status() == WorkflowStatus.FAILED) {
            log.error("Refund workflow failed: {}", handle.failureReason().orElse("unknown"));
        }
    }
}
```

---

## Stage output chaining

The return value of each stage is automatically injected as the first parameter
of the next stage — provided the types are compatible:

```java
@Stage(order = 1)
public OrderDetails lookupOrder(String orderId) {
    return orders.find(orderId);          // returns OrderDetails
}

@Stage(order = 2)
@Compensable(by = "cancelReservation")
public Reservation reserveInventory(OrderDetails order) {
    // 'order' is the return value of stage 1 — injected automatically
    return inventory.reserve(order.sku(), order.qty());
}

@Stage(order = 3)
@Compensable(by = "cancelCharge")
public ChargeReceipt chargeCard(Reservation reservation) {
    // 'reservation' is the return value of stage 2
    return payments.charge(reservation.customerId(), reservation.total());
}
```

If types do not match, or a stage returns `void`, the runtime passes `null`
for the next stage's parameter.

---

## Human approval gates

`@Gate(approvalRequired = true)` pauses the workflow before the annotated stage.
The workflow transitions to `PAUSED_AT_GATE` and waits indefinitely (or until
`timeoutSeconds`).

Two ways to approve or reject:

**REST endpoint** (auto-registered by `sagacity-workflows`):

```bash
# Approve
curl -X POST http://localhost:8080/sagacity/workflows/{runId}/gates/notifyCustomer/approve

# Reject with reason
curl -X POST http://localhost:8080/sagacity/workflows/{runId}/gates/notifyCustomer/reject \
  -H "Content-Type: application/json" \
  -d '{"reason": "customer requested cancellation"}'
```

**Programmatically** (from another service, Slack bot, etc.):

```java
workflowRuntime.approveGate(runId, "notifyCustomer");
workflowRuntime.rejectGate(runId, "notifyCustomer", "customer cancelled");
```

When rejected, the workflow fails and all completed `@Compensable` stages are
compensated in reverse order — exactly as if the stage itself had thrown an exception.

### Timeout

```java
@Gate(approvalRequired = true, timeoutSeconds = 3600)  // 1 hour
public void sendWireTransfer(PaymentDetails payment) { ... }
```

When the timeout fires, the workflow transitions to `FAILED` and compensation runs.

---

## Pre-flight checks

`@Check` runs one or more `StageCheck` implementations before the stage executes.
If any check fails, the stage never runs — the workflow fails and compensates.

This is where you put: budget enforcement, Jev risk scoring, precondition validation.

```java
@Stage(order = 3)
@Check({BudgetCheck.class, JevRiskCheck.class})
@Compensable(by = "revertCharge")
public ChargeReceipt chargeCard(PaymentDetails payment) { ... }
```

Implement `StageCheck`:

```java
@Component
public class BudgetCheck implements StageCheck {
    @Override
    public CheckResult check(StageCheckContext ctx) {
        double estimated = parseAmount(ctx.stageInput());
        if (estimated > dailyBudget.remaining()) {
            return CheckResult.fail("daily budget exceeded: " + estimated);
        }
        return CheckResult.pass();
    }
}
```

`StageCheckContext` provides: `runId()`, `workflowName()`, `stageName()`,
`stageOrder()`, `stageInput()` (the stage's input as a string).

---

## Compensation

Pair any `@Stage` with `@Compensable(by = "methodName")`. If the workflow fails
at any point, all completed stages with declared compensations are run in reverse
order.

```java
@Stage(order = 1)
@Compensable(by = "cancelReservation")
public Reservation reserveInventory(String sku) { ... }

@Stage(order = 2)
@Compensable(by = "cancelCharge")
public ChargeReceipt chargeCard(Reservation reservation) { ... }

@Stage(order = 3)    // no @Compensable — nothing to undo
public void sendConfirmation(ChargeReceipt receipt) {
    throw new RuntimeException("email service down");
}

@Compensation
public void cancelCharge(CompensationContext ctx) { ... }  // runs first

@Compensation
public void cancelReservation(CompensationContext ctx) { ... }  // runs second
```

Compensation failures are journaled and the run continues — partial cleanup
beats stopping halfway.

---

## Checking workflow status

```java
// Synchronous — blocks until completion
WorkflowRun run = runtime.run(myWorkflow, input);
System.out.println(run.status());        // COMPLETED or FAILED
System.out.println(run.completedStages()); // ["stage1", "stage2"]

// Asynchronous
WorkflowHandle handle = runtime.runAsync(myWorkflow, input);
handle.awaitCompletion(30, TimeUnit.SECONDS);

// List all runs
List<WorkflowRun> runs = runtime.allRuns();

// Find a specific run
Optional<WorkflowRun> run = runtime.findRun(runId);
```

**REST:**

```bash
GET /sagacity/workflows           # all runs
GET /sagacity/workflows/{runId}   # one run
```

Response:
```json
{
  "runId": "a1b2c3d4-...",
  "workflowName": "refund-request",
  "status": "PAUSED_AT_GATE",
  "currentStageOrder": 2,
  "pendingGateStageName": "notifyCustomer",
  "completedStages": ["issueRefund"],
  "startedAt": "2026-09-22T11:00:00Z",
  "completedAt": null
}
```

---

## Startup topology validation

The runtime validates all `@Workflow` beans when the Spring context starts.
These problems crash the application at startup, not during a production run:

- No `@Stage` methods on a `@Workflow` class
- Duplicate `@Stage` orders within one workflow
- `@Compensable(by = "x")` with no `@Compensation` method named `x`

---

## Workflow status transitions

```
RUNNING ──────────────────────────────────► COMPLETED
   │                                             ▲
   ├──► PAUSED_AT_GATE ──(approved)──────────────┘
   │         │
   │         └──(rejected / timeout)──► FAILED
   │                                      ▲
   └──(stage fails / check fails)─────────┤
                                  COMPENSATING
                                  (before FAILED)
```

---

## In-memory state (v0.3)

Workflow state is held in memory. Runs are lost on JVM restart. This is
intentional for v0.3 — most agent workflows are short-lived.

JDBC-backed durable state is on the roadmap for v0.4. The store is accessed
only through `WorkflowRuntime`, so the upgrade will be a configuration change,
not a code change.
