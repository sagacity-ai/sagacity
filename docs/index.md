---
hide:
  - navigation
  - toc
---

<div class="sg-hero" markdown>

# Sagacity

<p class="sg-tagline">
Human oversight and audit for Spring AI agents.
<strong>Pause before irreversible actions. Approve or reject. Unwind automatically. Every decision tamper-evident.</strong>
</p>

<div class="sg-cta" markdown>
[Get started](getting-started.md){ .md-button .md-button--primary }
[Approval gates](guides/approval-gates.md){ .md-button }
[View on GitHub](https://github.com/sumitvairagar/sagacity){ .md-button }
</div>

</div>

---

Your AI agents are making decisions that affect real people — approving transactions, sending emails, charging cards, updating records. Right now:

- nobody approved the irreversible action **before** it ran
- nothing undoes completed steps when something later fails
- there is no compliance-grade record of what happened, in what order, who approved what
- there is no answer when legal asks "can you prove your AI didn't act without authorisation?"

Sagacity is the governance layer that sits between your Spring AI agent and the actions it takes — inside your existing Spring Boot app, with no new infrastructure.

---

<div class="sg-grid" markdown>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--green"></span> Annotate your tools

Drop `@Compensable` on any Spring AI `@Tool` method. Sagacity intercepts every call,
journals it with a SHA-256 hash chain, and runs compensation in reverse if anything
fails downstream. No new infrastructure. No restructuring of your agent.

[Guide →](guides/compensation.md)

</div>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--amber"></span> Human approval gates

Mark a tool as `IRREVERSIBLE` and the agent pauses before it executes. Resume via REST,
the embedded UI, or programmatically. The approval is bound to the exact input the
approver saw — a re-planning agent cannot substitute a different payload.

[Guide →](guides/approval-gates.md)

</div>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--blue"></span> Tamper-evident audit

Every tool execution is journaled with SHA-256 hash chaining. Export as JSON Lines.
Verify the chain via REST to detect any modification made directly in the database.
Maps directly to EU AI Act Article 12.

[Guide →](guides/audit-and-verification.md)

</div>

</div>

---

<p class="sg-eyebrow">Two minutes to understand the shape</p>

## Annotate your tools, run inside a saga

Drop `@Compensable` on any Spring AI `@Tool` method. Sagacity intercepts every call,
journals it, and compensates on failure. One line change to how you run your agent.

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

```java
// Run your agent inside a saga — one line change
SagaResult<ChatResponse> result = sagacity.saga("order-123", () ->
    chatClient.prompt()
        .user("Place order for customer C-991")
        .toolCallbacks(sagacity.wrap(orderTools))   // ← wrap your tools
        .call().chatResponse());

// If chargeCard succeeded but a later step failed:
// → refundCharge runs automatically
// → every step is journaled with a SHA-256 hash chain
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
    <version>0.4.0</version>
</dependency>

<!-- Optional: declarative workflow engine -->
<dependency>
    <groupId>io.github.sumitvairagar</groupId>
    <artifactId>sagacity-workflows</artifactId>
    <version>0.4.0</version>
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

`0.4.0` ships durable JDBC-backed workflow state — runs survive JVM restarts, gates stay
open across deploys. The compensation, approval, audit, and workflow paths are covered by
**237 tests** across unit and integration suites, but the library has not been
battle-tested in production by anyone yet.

Read the [threat model](concepts/threat-model.md) before relying on the audit trail for
anything that matters.

Known gaps: no streaming tool-call support, no LangChain4j adapter.
See the [roadmap](about/roadmap.md).
