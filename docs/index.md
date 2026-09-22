---
hide:
  - navigation
  - toc
---

<div class="sg-hero" markdown>

# Sagacity

<p class="sg-tagline">
The reliability layer for Spring AI agents.
<strong>Declarative workflows, automatic compensation, human approval gates, and a tamper-evident audit trail — in annotations.</strong>
</p>

<div class="sg-cta" markdown>
[Get started](getting-started.md){ .md-button .md-button--primary }
[Workflows guide](guides/workflows.md){ .md-button }
[View on GitHub](https://github.com/sumitvairagar/sagacity){ .md-button }
</div>

</div>

---

AI agents do real work: they reserve inventory, charge cards, send emails, update CRMs. When step 4 of 5 fails:

- the side effects from steps 1–3 are **live in production**
- nothing undoes them automatically
- there is no compliance-grade record of what happened
- nobody approved the irreversible action in step 3

Sagacity fixes all four — as a library, inside your existing Spring Boot app, with no new infrastructure.

---

<div class="sg-grid" markdown>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--green"></span> Verifiable workflows

Declare multi-step agent workflows with `@Workflow`, `@Stage`, `@Gate`, and `@Check`.
The runtime executes stages in order, chains outputs as inputs, and compensates completed
stages in reverse when anything fails. Topology is validated at startup — bad definitions
crash the app, not a production run.

[Guide →](guides/workflows.md)

</div>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--green"></span> Automatic compensation

Every `@Stage` or `@Tool` paired with `@Compensable` gets an automatic undo when the
workflow fails. Compensations run in reverse execution order, each outcome journaled.
A failing compensation is recorded and the run continues — partial cleanup beats none.

[Guide →](guides/compensation.md)

</div>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--amber"></span> Human approval gates

Mark a stage with `@Gate(approvalRequired = true)` and the workflow pauses before it
executes. Resume via REST or programmatically. The approval is bound to the exact input
the approver saw — a re-planning agent cannot substitute a different payload.

[Guide →](guides/approval-gates.md)

</div>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--blue"></span> Tamper-evident audit

Every stage execution is journaled with SHA-256 hash chaining. Export as JSON Lines.
Verify the chain via REST to detect any modification made directly in the database.
Maps directly to EU AI Act Article 12.

[Guide →](guides/audit-and-verification.md)

</div>

</div>

---

<p class="sg-eyebrow">Two minutes to understand the shape</p>

## Wrap individual tools, or declare a workflow

**Option A — annotate individual tools.** Sagacity intercepts every Spring AI tool call,
journals it, and compensates on failure. Drop `@Compensable` on any `@Tool` method.

```java
@Tool(description = "Charge the customer")
@Compensable(by = "refundCharge")
public String chargeCard(String amount, String customerId) {
    return payments.charge(customerId, amount);   // returns "ch_1M2n3"
}

@Compensation
public void refundCharge(CompensationContext ctx) {
    payments.refund(ctx.result());               // ctx.result() = "ch_1M2n3"
}
```

**Option B — declare a verifiable workflow.** Define the entire multi-step process as
annotated stages. Stage outputs chain as inputs automatically. Gates, checks, and
compensation are all first-class.

```java
@Workflow("refund-approval")
@Component
public class RefundWorkflow {

    @Stage(order = 1)
    @Compensable(by = "cancelValidation")       // undo if anything fails later
    public String validateRefund(String orderId) {
        return validation.check(orderId);        // returns "val-8821"
    }

    @Stage(order = 2)
    @Compensable(by = "reverseRefund")
    public String issueRefund(String validationId) {
        // 'validationId' injected automatically from stage 1's return value
        return payments.refund(validationId);    // returns "ref-4492"
    }

    @Stage(order = 3)
    @Gate(approvalRequired = true,               // workflow pauses here
          reason = "Compliance must approve before customer is notified")
    public String notifyCompliance(String refundId) {
        // POST /sagacity/workflows/{runId}/gates/notifyCompliance/approve
        return compliance.log(refundId);
    }

    @Stage(order = 4)
    public void sendConfirmation(String complianceRef) {
        email.send(complianceRef, "Your refund is confirmed");
    }

    @Compensation
    public void cancelValidation(CompensationContext ctx) { validation.cancel(ctx.result()); }

    @Compensation
    public void reverseRefund(CompensationContext ctx) { payments.reverse(ctx.result()); }
}
```

```java
// Run async — pauses at stage 3 until approved
WorkflowHandle handle = workflowRuntime.runAsync(refundWorkflow, "ORDER-88210");
workflowRuntime.approveGate(handle.runId(), "notifyCompliance");
handle.awaitCompletion(30, TimeUnit.MINUTES);
// Every stage journaled. Failure at any stage compensates in reverse.
```

<p class="sg-eyebrow">The distinction that matters</p>

## How Sagacity relates to Temporal

Temporal is infrastructure. It solves **durable execution** — if your process crashes, your workflow replays from exactly where it stopped. It also handles distributed workers, cross-service orchestration, and horizontal scale. Temporal just raised $550M and ships a Spring AI integration (`temporal-spring-ai`) that makes model calls and tool executions durable activities. It is serious, production-grade infrastructure.

That is not the same problem Sagacity solves.

A workflow that resumes perfectly after a crash still leaves you with:
- A charged card when the business logic says the order should be abandoned
- An inventory reservation nobody will ever release
- An email already sent to a customer about a transaction that failed

**Crash recovery cannot undo a side effect. A refund is not a retry.**

Sagacity answers a different question: when your agent succeeds technically but the business says "this should not have happened," what gets unwound, who approved it before it ran, and what is the tamper-evident record?

| | Temporal | Sagacity |
|---|---|---|
| Durable execution (survive process crash) | ✅ cluster-backed | ❌ v0.4 adds JDBC state, not the same |
| Distributed workers, horizontal scale | ✅ | ❌ single JVM |
| Spring AI native integration | ✅ `temporal-spring-ai` (Preview) | ✅ `sagacity-spring-boot-starter` |
| Undo side effects on **business** failure | ⚠️ possible via child workflow pattern | ✅ `@Compensable` — first-class, annotation-driven |
| Tamper-evident SHA-256 audit trail | ❌ event history is operational, not compliance-grade | ✅ append-only, hash-chained, verifiable |
| EU AI Act Article 12 compliance | ❌ | ✅ |
| Human approval gates before irreversible actions | ❌ | ✅ `@Gate(approvalRequired=true)` |
| New infrastructure to run | ✅ cluster or Temporal Cloud (~$200+/month) | ❌ library — add a dependency |
| Adopt without rewriting agent code | ❌ must model everything as Workflows + Activities | ✅ annotate existing Spring AI tools |

**They are complementary.** A production system could use both — Temporal for durability and scale, Sagacity for compensation semantics, approval gates, and the compliance audit trail. The things Temporal's event history records and the things Sagacity's hash-chained journal records serve different audiences: Temporal's history is for engineers debugging a stuck workflow; Sagacity's journal is for compliance officers proving what an AI agent did, in what order, and who approved it.

<p class="sg-eyebrow">Install</p>

## Add the dependency

```xml
<!-- Core: compensation + audit trail -->
<dependency>
    <groupId>io.github.sumitvairagar</groupId>
    <artifactId>sagacity-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>

<!-- Optional: declarative workflow engine -->
<dependency>
    <groupId>io.github.sumitvairagar</groupId>
    <artifactId>sagacity-workflows</artifactId>
    <version>0.3.0</version>
</dependency>
```

Requires Java 17+, Spring AI 2.0.0, Spring Boot 4.0.x.

!!! warning "Spring Boot 4 is required, not optional"
    Spring AI 2.0.0 compiles against Spring Framework 7. Spring Boot 3.5.x
    resolves Spring Framework 6.2 and will downgrade `spring-core` underneath
    Spring AI, failing at runtime with
    `NoClassDefFoundError: org/springframework/core/Nullness`.

<p class="sg-eyebrow">Before you rely on it</p>

## Status

`0.3.0` ships the workflow engine. The compensation, approval, audit, and workflow paths
are covered by **202 tests** across unit and integration suites, but the library has not
been battle-tested in production by anyone yet.

Read the [threat model](concepts/threat-model.md) before relying on the audit trail for
anything that matters. Workflow state is in-memory in v0.3 — runs are lost on JVM restart.
JDBC-backed durable state is the v0.4 priority.

Known gaps: no streaming tool-call support, no LangChain4j adapter, no approval dashboard UI.
See the [roadmap](about/roadmap.md).
