# Sagacity

**SAGA compensation for Spring AI tool calls, with a tamper-evident audit trail.**

Your agent charged a card, reserved inventory, then failed on step 4. The first
three steps are live in production and nothing undoes them. Sagacity walks the
journal backward and runs the compensation you declared for each one.

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

---

## What this is, and is not

Temporal, Restate and DBOS solve **durability** — resuming a workflow after a
crash. That is a different problem from **compensation** — undoing effects that
already happened and cannot be replayed away. A refund is not a retry.

Sagacity solves compensation and evidence. It is not a workflow engine, not an
agent framework, and does not replace the above — it sits inside Spring AI's
tool-calling path and records what happened.

## Three things it does

<div class="grid cards" markdown>

- **Compensation**

    Declare an undo per tool. On failure, compensations run in reverse order of
    execution, each outcome journaled. A failing compensation is recorded and
    the run continues — partial cleanup beats none.

    [Guide →](guides/compensation.md)

- **Approval gates**

    Tools marked `IRREVERSIBLE` suspend the saga until a human approves. The
    approval is bound to the exact payload that was shown to the approver, so a
    re-planned agent cannot substitute a different one.

    [Guide →](guides/approval-gates.md)

- **Tamper-evident audit**

    Every tool call is journaled to an append-only Postgres table with SHA-256
    hash chaining. Export as JSON Lines; verify the chain to detect edits made
    directly in the database.

    [Guide →](guides/audit-and-verification.md)

</div>

## Install

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

[Get started in five minutes →](getting-started.md){ .md-button .md-button--primary }

## Status

`0.1.0` is a first release. The compensation, approval and audit paths are
covered by 75 unit tests and 11 integration tests against real Postgres, but the
library has not been battle-tested in production by anyone yet. Treat it
accordingly, and read the [threat model](concepts/threat-model.md) before relying
on the audit trail for anything that matters.

Known gaps: no streaming tool-call support, no LangChain4j adapter, no UI. See
the [roadmap](about/roadmap.md).
