# Getting started

Build an agent that reserves inventory, charges a card, and correctly undoes both
when a later step fails. About five minutes.

## 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.sumitvairagar</groupId>
    <artifactId>sagacity-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

The starter brings in `sagacity-core` and `sagacity-spring-ai`. If you are not
using Spring Boot, depend on `sagacity-spring-ai` directly and construct
`Sagacity` yourself.

## 2. Declare tools and their undo

A Sagacity tool is an ordinary Spring AI `@Tool` with one extra annotation
naming the method that reverses it.

```java
class OrderTools {

    @Tool(description = "Reserve inventory for an item")
    @Compensable(by = "releaseInventory")
    public String reserveInventory(String sku, String quantity) {
        return inventory.reserve(sku, Integer.parseInt(quantity));  // "res-8891"
    }

    @Compensation
    public void releaseInventory(CompensationContext ctx) {
        inventory.release(ctx.result());        // "res-8891"
    }

    @Tool(description = "Charge the customer's card")
    @Compensable(by = "refundCharge")
    public String chargeCard(String amount, String customerId) {
        return payments.charge(customerId, amount);
    }

    @Compensation
    public void refundCharge(CompensationContext ctx) {
        payments.refund(ctx.result());
    }

    @Tool(description = "Dispatch the order to the warehouse")
    public String dispatchOrder(String orderId) {
        return warehouse.dispatch(orderId);     // this one is going to fail
    }
}
```

`CompensationContext` carries what the original call did, so the undo knows what
to target:

| Accessor | Holds |
|---|---|
| `sagaId()` | the saga this effect belonged to |
| `toolName()` | the tool that produced the effect |
| `input()` | the exact JSON arguments the tool was called with |
| `result()` | what the tool returned — usually the handle you need to undo it |

!!! tip "Return something you can undo with"
    `releaseInventory` above works because `reserveInventory` returned the
    reservation ID. A tool returning `"ok"` gives its compensation nothing to
    work with. Return the identifier.

## 3. Wrap the tools and run inside a saga

```java
Sagacity sagacity = Sagacity.create();                 // in-memory journal
ToolCallback[] tools = sagacity.wrap(new OrderTools());

SagaResult<ChatResponse> result = sagacity.saga("order-123", () ->
        chatClient.prompt()
            .user("Place order 123: reserve SKU-9, charge $100, dispatch it")
            .toolCallbacks(tools)
            .call()
            .chatResponse());
```

`wrap()` registers the compensations and returns callbacks that journal every
call. Outside a saga scope those callbacks are pass-throughs, so the same tool
beans stay usable in non-saga flows.

## 4. What happens when step 3 fails

`dispatchOrder` throws. Sagacity sees the failure, walks the journal backward,
and runs the declared compensations in reverse order of execution:

```
reserveInventory  EXECUTED     res-8891
chargeCard        EXECUTED     ch_1M2n3
dispatchOrder     FAILED       warehouse unreachable
chargeCard        COMPENSATED  refunded ch_1M2n3      ← reverse order
reserveInventory  COMPENSATED  released res-8891
```

```java
if (result.status() == SagaStatus.COMPENSATED) {
    result.report().outcomes().forEach(o ->
        log.info("{} seq={} {}", o.toolName(), o.seq(), o.result()));
}
```

`SagaResult.status()` is one of:

| Status | Meaning |
|---|---|
| `COMPLETED` | the agent finished, nothing was compensated |
| `COMPENSATED` | something failed and every compensation succeeded |
| `COMPENSATION_FAILED` | something failed and at least one undo also failed — **needs a human** |
| `AWAITING_APPROVAL` | an `IRREVERSIBLE` tool is waiting on a person |

!!! danger "COMPENSATION_FAILED is not a normal outcome"
    It means the system is in a state nothing could clean up — money moved and
    the refund also failed. The journal holds the evidence of exactly what is
    dirty. Alert on this status.

### Failures Spring AI swallows

Spring AI's `DefaultToolCallingManager` catches `ToolExecutionException` and
feeds the message back to the model as text, so the agent may cheerfully carry
on after a tool failed. Sagacity decorates at the *callback* level, inside that
catch, so it sees the raw failure first and marks the saga failed even when the
model never notices. Compensation still runs.

## 5. Use a real journal

`Sagacity.create()` uses an in-memory journal — fine for tests, useless for
evidence, and it writes no hash chain. For anything real, give it Postgres:

```java
Sagacity sagacity = Sagacity.create(new PostgresSideEffectJournal(dataSource));
```

With the Spring Boot starter this is automatic: if a `DataSource` bean exists,
the auto-configuration wires the Postgres journal and creates the schema on
startup. See [Configuration](reference/configuration.md).

## Next

- [Approval gates](guides/approval-gates.md) — for tools that cannot be undone at all
- [Audit and verification](guides/audit-and-verification.md) — proving what happened
- [Production checklist](guides/production-checklist.md) — before you point this at real money
