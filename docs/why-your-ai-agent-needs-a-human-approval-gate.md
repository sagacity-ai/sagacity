# Why your AI agent needs a human approval gate

Your AI agent just sent a wire transfer. Nobody approved it.

Not because you forgot to build approval. Because there was no obvious place to put it. Your agent runs inside a `ChatClient`, calls tools, and the tools just... execute. By the time you want to add a "pause here and ask a human" step, you're looking at a tangle of callbacks, state machines, and ad-hoc flags.

This is the gap Sagacity fills.

---

## The problem in concrete terms

Imagine an order-fulfilment agent. It:

1. Validates the order
2. Reserves inventory
3. Charges the card
4. Sends a confirmation email

Step 3 is irreversible. If the agent charges the wrong card — wrong amount, wrong customer, duplicate charge — you cannot un-send that money without a manual reversal process, a support ticket, and an unhappy customer.

Most Spring AI agents have no mechanism to pause before step 3 and ask a human to confirm. The tool just runs.

---

## What Sagacity adds

Two annotations. That's it.

```java
@Tool(description = "Charge the customer's card")
@Compensable(reversibility = Reversibility.IRREVERSIBLE)
public String chargeCard(String orderId, BigDecimal amount) {
    return payments.charge(orderId, amount);
}
```

`@Compensable(reversibility = Reversibility.IRREVERSIBLE)` tells Sagacity: before this tool executes, pause the saga and wait for human approval.

The agent stops. A pending approval appears in the REST API (and the embedded UI at `/sagacity/ui`). A human reviews the payload and clicks Approve or Reject. If approved, the tool executes and the saga continues. If rejected, Sagacity automatically compensates every step that already ran — in reverse order.

---

## The full workflow pattern

For multi-step processes, `sagacity-workflows` gives you a declarative workflow engine:

```java
@Workflow("refund-approval")
@Component
public class RefundWorkflow {

    @Stage(order = 1)
    @Compensable(by = "cancelValidation")
    public String validateRefund(String orderId) {
        return validation.check(orderId);
    }

    @Stage(order = 2)
    @Compensable(by = "reverseRefund")
    public String issueRefund(String validationId) {
        return payments.refund(validationId);
    }

    @Stage(order = 3)
    @Gate(approvalRequired = true,
          reason = "Compliance must approve before customer is notified")
    public String notifyCompliance(String refundId) {
        return compliance.log(refundId);
    }

    @Compensation
    public void cancelValidation(CompensationContext ctx) {
        validation.cancel(ctx.result());
    }

    @Compensation
    public void reverseRefund(CompensationContext ctx) {
        payments.reverse(ctx.result());
    }
}
```

Stages execute in order. Each stage's return value is automatically injected as the next stage's input. The `@Gate` pauses execution — the workflow sits in `PAUSED_AT_GATE` state until a human approves via `POST /sagacity/workflows/{runId}/gates/notifyCompliance/approve`. If anything fails at any stage, compensations run in reverse.

---

## The audit trail

Every tool call, every approval decision, every compensation — journaled automatically with a SHA-256 hash chain. You can verify the chain hasn't been tampered with:

```
GET /sagacity/audit/{sagaId}/verify
```

This is what EU AI Act Article 12 requires: tamper-evident, traceable logs for high-risk AI systems. Sagacity produces this as a side effect of normal operation — you don't build it separately.

---

## What it takes to add this to an existing Spring AI app

One dependency:

```xml
<dependency>
    <groupId>io.github.sumitvairagar</groupId>
    <artifactId>sagacity-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

One table in your existing database (auto-created on startup).

Annotate your tools with `@Compensable`. Wrap your `ChatClient` call with `sagacity.saga(...)`. Done.

No new infrastructure. No separate cluster. No rewriting your agent code.

---

## Links

- [GitHub](https://github.com/sagacity-ai/sagacity)
- [Getting started guide](getting-started.md)
- [Maven Central](https://central.sonatype.com/artifact/io.github.sumitvairagar/sagacity-spring-boot-starter)
- [Verifiable workflows guide](guides/workflows.md)
