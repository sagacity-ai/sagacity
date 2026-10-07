# Why your AI agent needs a human approval gate

Your AI agent just sent a wire transfer. Nobody approved it.

Not because you forgot to build approval. Because there was no obvious place to put it. Your agent runs inside a `ChatClient`, calls tools, and the tools just... execute. By the time you want to add a "pause here and ask a human" step, you're looking at a tangle of callbacks, state machines, and ad-hoc flags.

This is the gap Sagacity fills.

---

## The problem in concrete terms

Imagine an expense-claim agent. It:

1. Validates the claim against policy
2. Deducts the approved amount from the department budget
3. Transfers the money to the employee's account

Step 3 is irreversible. Once money leaves the company account, you cannot recall it without a manual reversal process, a support ticket, and an unhappy finance team.

Most Spring AI agents have no mechanism to pause before step 3 and ask a human to confirm. The tool just runs.

---

## What Sagacity adds

Two annotations. That's it.

```java
@Tool(description = "Transfer the approved amount to the employee bank account")
@Compensable(reversibility = Reversibility.IRREVERSIBLE)
public String transferMoney(String deductionRef, String employeeId) {
    return payments.wire(employeeId, deductionRef);
}
```

`@Compensable(reversibility = Reversibility.IRREVERSIBLE)` tells Sagacity: before this tool executes, pause the saga and wait for human approval.

The agent stops. A pending approval appears in the REST API and the embedded UI at `/sagacity/ui`. A compliance officer reviews the payload and clicks Approve or Reject. If approved, the transfer executes and the saga continues. If rejected, Sagacity automatically compensates every step that already ran — in reverse order.

---

## What happens when the human rejects

If the compliance officer rejects the transfer — "this employee is on leave, claim submitted in error" — Sagacity unwinds:

```
Step 2: deductBudget     → COMPENSATED  ↩  budget restored
Step 1: validateClaim    → COMPENSATED  ↩  validation cancelled
Step 3: transferMoney    → NEVER RAN    ✅  no money moved
```

No orphaned budget deduction. No money moved. Every step journaled with a SHA-256 hash chain.

---

## What happens when a step fails without a human

If step 2 fails — budget limit exceeded — Sagacity detects the failure and compensates automatically, even when Spring AI's `DefaultToolCallingManager` swallows the exception and feeds it back to the model as text. Sagacity decorates at the callback level, inside that catch — it always sees the raw failure first.

```
Step 1: validateClaim    → COMPENSATED  ↩  validation cancelled
Step 2: deductBudget     → FAILED       ✖  budget limit exceeded
```

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
- [Approval gates guide](guides/approval-gates.md)
- [Maven Central](https://central.sonatype.com/artifact/io.github.sumitvairagar/sagacity-spring-boot-starter)
