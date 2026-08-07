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
