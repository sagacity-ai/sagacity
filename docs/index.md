---
hide:
  - navigation
  - toc
---

<div class="sg-hero" markdown>

# Sagacity

<p class="sg-tagline">
Your agent charged the card, reserved the inventory, then failed on step four.
<strong>Sagacity undoes what already happened — and produces the evidence.</strong>
</p>

<div class="sg-cta" markdown>
[Get started](getting-started.md){ .md-button .md-button--primary }
[Architecture](concepts/architecture.md){ .md-button }
[View on GitHub](https://github.com/sumitvairagar/sagacity){ .md-button }
</div>

</div>

```java
@Tool(description = "Charge the customer")
@Compensable(by = "refundCharge")
public String chargeCard(String amount, String customerId) {
    return payments.charge(customerId, amount);
}

@Compensation
public void refundCharge(CompensationContext ctx) {
    payments.refund(ctx.result());
}
```

That is the whole developer-facing idea. One annotation names the undo.

<div class="sg-grid" markdown>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--green"></span> Compensation

Declare an undo per tool. On failure, compensations run in reverse order of
execution, each outcome journaled. A failing compensation is recorded and the
run continues — partial cleanup beats none.

[Guide →](guides/compensation.md)

</div>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--amber"></span> Approval gates

Tools marked `IRREVERSIBLE` suspend the saga until a human approves. The approval
is bound to the exact payload the approver saw, so a re-planning agent cannot
substitute a different one.

[Guide →](guides/approval-gates.md)

</div>

<div class="sg-card" markdown>

### <span class="sg-dot sg-dot--blue"></span> Tamper-evident audit

Every tool call is journaled to an append-only Postgres table with SHA-256 hash
chaining. Export as JSON Lines; verify the chain to detect edits made directly in
the database.

[Guide →](guides/audit-and-verification.md)

</div>

</div>

---

<p class="sg-eyebrow">The distinction that matters</p>

## Compensation is not durability

Temporal, Restate and DBOS solve **durability** — resuming a workflow after a
crash. That is a different problem from **compensation** — undoing effects that
already happened and cannot be replayed away.

A workflow that resumes perfectly still leaves you with a charged card when the
business logic says the order must be abandoned. A refund is not a retry.

Sagacity solves compensation and evidence. It is not a workflow engine, not an
agent framework, and does not replace the above — it sits inside Spring AI's
tool-calling path and records what happened.

<p class="sg-eyebrow">Install</p>

## Add the dependency

```xml
<dependency>
    <groupId>io.github.sumitvairagar</groupId>
    <artifactId>sagacity-spring-boot-starter</artifactId>
    <version>0.1.0</version>
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

`0.1.0` is a first release. The compensation, approval and audit paths are
covered by 88 unit tests and 18 integration tests against real Postgres, but the
library has not been battle-tested in production by anyone yet.

Read the [threat model](concepts/threat-model.md) before relying on the audit
trail for anything that matters — it states plainly what the hash chain does and
does not defend against, including the parts that are unflattering.

Known gaps: no streaming tool-call support, no LangChain4j adapter, no UI. See
the [roadmap](about/roadmap.md).
