# Guide 2: The Annotations — Developer-Facing API

The annotations are what a developer sees first. Three annotations + one enum + one record define the entire contract between your tool code and Sagacity's runtime.

## Files

```
sagacity-core/src/main/java/dev/sagacity/core/
├── annotation/
│   ├── Compensable.java     ← "this tool has side effects that can be undone"
│   └── Compensation.java    ← marker: "this IS the undo method"
├── Reversibility.java       ← enum: how undoable is the side effect
└── compensation/
    └── CompensationContext.java  ← data passed to the undo method
```

## What a Developer Writes

```java
@Tool(description = "Reserve inventory")
@Compensable(by = "releaseInventory")
public String reserveInventory(String productId, int quantity) {
    inventoryService.reserve(productId, quantity);
    return "reservation-84";
}

@Compensation
public void releaseInventory(CompensationContext ctx) {
    // ctx.input()  = original JSON: {"productId":"p-1","quantity":5}
    // ctx.result() = "reservation-84" (what the tool returned)
    inventoryService.release(extractId(ctx.result()));
}
```

---

## @Compensable

```java
@Retention(RetentionPolicy.RUNTIME)    // readable via reflection at runtime
@Target(ElementType.METHOD)             // only on methods
public @interface Compensable {
    String by() default "";                            // undo method name
    Reversibility reversibility() default COMPENSATABLE;  // how undoable
}
```

### Design decisions

**`by = "methodName"` (string, not class reference):** The compensation method lives in the same class as the tool. A string lookup keeps the developer's code compact — tool and undo live side by side, no separate class needed.

**`by() default ""`:** For IRREVERSIBLE tools there IS no undo method:
```java
@Compensable(reversibility = Reversibility.IRREVERSIBLE)
public String sendWireTransfer(...) { }
// No "by" — nothing to call. Saga suspends for human approval instead.
```

**`reversibility` defaults to `COMPENSATABLE`:** Most real-world tools have an imperfect-but-acceptable undo. It's the common case, so it's the default.

---

## @Compensation

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Compensation { }
```

An empty marker annotation. The actual binding is through `@Compensable(by = "name")`.

Why it exists:
1. **Readability** — scanning a class, you instantly see which methods are undo logic
2. **Tooling** — IDEs/static analysis can verify every `by` reference has `@Compensation`
3. **Future-proofing** — v2 might add `@Compensation(timeout = "5s")` or `@Compensation(retries = 3)`

---

## Reversibility

```java
public enum Reversibility {
    REVERSIBLE,      // perfect inverse (insert row → delete it)
    COMPENSATABLE,   // imperfect but acceptable (reserve → release)
    IRREVERSIBLE     // no undo (wire transfer, email sent)
}
```

From distributed systems theory (SAGA pattern paper). The behavioral difference:

| Level | Sagacity behavior |
|-------|-------------------|
| `REVERSIBLE` | Executes immediately. On saga failure: runs undo method. |
| `COMPENSATABLE` | Executes immediately. On saga failure: runs undo method. |
| `IRREVERSIBLE` | **Suspends saga before executing.** Waits for human approval. |

Today, `REVERSIBLE` and `COMPENSATABLE` behave identically at runtime. The distinction exists for:
- Documentation (developers classify their tools honestly)
- Future: different retry/timeout policies per level
- Audit: reports can flag "compensated an imperfect undo — manual review recommended"

---

## CompensationContext

```java
public record CompensationContext(
    String sagaId,     // which saga failed
    String toolName,   // which tool's effect we're undoing
    String input,      // the original JSON input to the tool
    String result      // what the tool returned (the "receipt")
) { }
```

**Why a record?** Immutable value object — computed once, passed to undo, never mutated. Free `equals()`, `hashCode()`, `toString()`.

**Why these four fields?** To undo something, you need:
- `input` → know WHAT was done (product ID, quantity, etc.)
- `result` → know the RECEIPT to cancel (reservation ID, order ID, etc.)
- `sagaId` + `toolName` → metadata for logging or conditional undo logic

### Example: parsing context in a compensation method

```java
@Compensation
public void releaseInventory(CompensationContext ctx) {
    JsonObject input = JsonParser.parseString(ctx.input()).getAsJsonObject();
    String productId = input.get("productId").getAsString();
    
    String reservationId = ctx.result().replace("\"", "");
    inventoryService.release(reservationId, productId);
}
```

---

## How It Connects to the Runtime

```
Declaration (this lesson)          Runtime (lessons 4-6)
────────────────────────           ─────────────────────
@Compensable(by="undo")     →     CompensationScanner reads it at wrap time
                                   CompensationRunner calls undo(ctx) on failure

Reversibility.IRREVERSIBLE  →     SagacityToolCallback checks BEFORE executing
                                   Suspends saga, creates ApprovalRequest

CompensationContext         →     Built from JournalEntry data when compensating
                                   Passed to the undo method
```

The annotations are pure metadata — they express WHAT and HOW. The runtime reads them and acts.
