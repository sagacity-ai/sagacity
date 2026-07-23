# Guide 3: The Journal

The journal is the heart of Sagacity. Every tool call — intent, execution, failure, compensation — gets recorded here. Without the journal, there's no compensation (you don't know what to undo) and no audit trail.

## Files

```
sagacity-core/src/main/java/dev/sagacity/core/journal/
├── Phase.java                      ← lifecycle states of a tool call
├── JournalEntry.java               ← one immutable row
├── SideEffectJournal.java          ← the contract (interface)
└── InMemorySideEffectJournal.java  ← test/dev implementation
```

## The Concept

Think of the journal like a bank transaction log. Your bank doesn't just store your balance — it stores every deposit and withdrawal as an append-only sequence. Sagacity does the same for tool calls:

- **Before** a tool runs → record `INTENT`
- **After** success → record `EXECUTED`
- **After** failure → record `FAILED`
- **During compensation** → record `COMPENSATED` or `COMPENSATION_FAILED`

This gives you:
1. **Compensation data** — what to undo (read back from journal entries)
2. **Audit trail** — what happened, when, in what order
3. **Crash safety** — if you crash between INTENT and EXECUTED, you know something is incomplete

---

## Phase — The State Machine

```
                    ┌─── EXECUTED ─── COMPENSATED
                    │                      │
INTENT ─────────────┤                      └── COMPENSATION_FAILED
                    │
                    └─── FAILED

(IRREVERSIBLE tools):
AWAITING_APPROVAL ─── APPROVED ─── INTENT ─── EXECUTED
                  └── REJECTED
```

| Phase | Meaning | `payload` contains |
|-------|---------|-------------------|
| `INTENT` | "About to call this tool" | `""` |
| `EXECUTED` | "Tool returned successfully" | Tool's return value |
| `FAILED` | "Tool threw an exception" | Error message |
| `COMPENSATED` | "Undo ran successfully" | Reference to undone entry |
| `COMPENSATION_FAILED` | "Undo itself broke" | Compensation error |
| `AWAITING_APPROVAL` | "IRREVERSIBLE — waiting for human" | `""` |
| `APPROVED` | "Human said yes" | Approver identity |
| `REJECTED` | "Human said no" | Approver identity |

### Why INTENT before EXECUTED?

**Write-ahead log pattern.** If the JVM crashes AFTER the tool runs but BEFORE we journal EXECUTED, we have an orphaned side effect with no record. With INTENT recorded first, we can detect "INTENT with no matching EXECUTED/FAILED" = something may have run but we didn't capture the outcome.

---

## JournalEntry — One Row

```java
public record JournalEntry(
    String sagaId,      // groups entries into a saga
    long seq,           // strictly increasing within saga (1, 2, 3...)
    String toolName,    // which tool
    Phase phase,        // lifecycle state
    String input,       // JSON passed to the tool
    String payload,     // result/error depending on phase
    Instant timestamp,  // UTC
    String hash         // SHA-256 chain link ("" for InMemory)
) { }
```

### Sequence is per-saga

Saga-A has entries #1, #2, #3; Saga-B also starts at #1. Benefits:
- No global contention between parallel sagas
- Hash chain is per-saga
- Verify one saga without reading others

### Two constructors

```java
// Postgres (has hash): new JournalEntry(..., hash)
// InMemory (no hash):  new JournalEntry(...) → hash defaults to ""
```

---

## SideEffectJournal — The Interface

```java
public interface SideEffectJournal {
    JournalEntry append(String sagaId, String toolName, Phase phase,
                        String input, String payload);
    List<JournalEntry> entries(String sagaId);
}
```

Two methods. Append and read. That's the entire contract.

**Why so minimal?** Interface segregation. Consumers (SagacityToolCallback, CompensationRunner) don't need to know about sequence assignment, hashing strategy, or storage mechanism. You swap InMemory for Postgres with zero code changes elsewhere.

---

## InMemorySideEffectJournal

```java
public final class InMemorySideEffectJournal implements SideEffectJournal {
    private final Map<String, List<JournalEntry>> entriesBySaga = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
}
```

### Thread-safety design

| Mechanism | Protects |
|-----------|----------|
| `ConcurrentHashMap` | Concurrent saga creation (different threads, different sagas) |
| `AtomicLong` per saga | Sequence increment without locking |
| `synchronized(list)` | Two threads appending to the SAME saga |
| `List.copyOf()` on read | Returns immutable snapshot — caller can't mutate journal |

Why not `CopyOnWriteArrayList`? It copies the entire array on every write — too expensive for an append-heavy workload.

---

## How the Journal Gets Used

```java
// SagacityToolCallback.call() (Lesson 6):
journal.append(sagaId, toolName, INTENT, toolInput, "");
try {
    String result = delegate.call(toolInput);
    journal.append(sagaId, toolName, EXECUTED, toolInput, result);
} catch (Exception e) {
    journal.append(sagaId, toolName, FAILED, toolInput, e.getMessage());
    throw e;
}

// CompensationRunner (Lesson 4):
// Reads entries, finds EXECUTED ones, runs undo in reverse, journals COMPENSATED
```

The journal is the single source of truth. Everything else reads from it.
