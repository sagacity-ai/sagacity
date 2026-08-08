# Journal schema

```sql
CREATE TABLE IF NOT EXISTS side_effect_journal (
    saga_id     TEXT        NOT NULL,
    seq         BIGINT      NOT NULL,
    tool_name   TEXT        NOT NULL,
    phase       TEXT        NOT NULL,
    input       TEXT        NOT NULL DEFAULT '',
    payload     TEXT        NOT NULL DEFAULT '',
    timestamp   TIMESTAMP   NOT NULL,
    hash        CHAR(64)    NOT NULL,
    PRIMARY KEY (saga_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_journal_saga_id ON side_effect_journal (saga_id);
```

| Column | Holds |
|---|---|
| `saga_id` | Scope of one agent task. Chains are per saga. |
| `seq` | Strictly increasing within a saga, starting at 1, gap-free. |
| `tool_name` | Tool that produced the entry, or `approval-gate` for decisions. |
| `phase` | See below. |
| `input` | Tool input JSON, verbatim. |
| `payload` | Result on `EXECUTED`, error on `FAILED`, approver on `APPROVED`, else empty. |
| `timestamp` | UTC, **microsecond precision**. |
| `hash` | SHA-256 over this entry and the previous hash. |

## Phases

| Phase | Written when |
|---|---|
| `INTENT` | Immediately **before** a tool executes — "we are about to do this". |
| `EXECUTED` | The tool returned. `payload` holds the result. |
| `FAILED` | The tool threw. Effect state is unknown. |
| `COMPENSATED` | The declared compensation ran cleanly. |
| `COMPENSATION_FAILED` | The compensation itself threw — the effect is still live. |
| `AWAITING_APPROVAL` | An `IRREVERSIBLE` tool suspended the saga. |
| `APPROVED` | A human approved. `payload` is `approver=<identity>`. |
| `REJECTED` | A human rejected, or verification refused execution. |

`INTENT` is written before execution deliberately. An `INTENT` with no following
`EXECUTED` or `FAILED` is the signal that a process died mid-call and a human
needs to determine what actually happened.

## Why `TIMESTAMP`, not `TIMESTAMPTZ`

Postgres `TIMESTAMP` stores microseconds. Values are truncated to microseconds
*before* hashing so the hashed value is byte-identical to what is read back.
Hashing a nanosecond-precision `Instant` would break verification for every
persisted saga on platforms whose clock is finer than a microsecond. All values
are UTC.

## The approval request table

```sql
CREATE TABLE IF NOT EXISTS sagacity_approval_request (
    saga_id      TEXT        NOT NULL,
    journal_seq  BIGINT      NOT NULL,
    tool_name    TEXT        NOT NULL,
    input        TEXT        NOT NULL DEFAULT '',
    input_hash   CHAR(64)    NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    PRIMARY KEY (saga_id, journal_seq)
);
```

Holds approvals awaiting a decision, so they survive a restart. Unlike the
journal this table is **mutable**: rows are deleted once consumed or rejected,
and re-requesting the same `(saga_id, journal_seq)` replaces rather than
duplicating.

It is working state, not evidence. The durable record of what was proposed,
approved and executed lives in the journal, where the hash chain covers it.
`input_hash` here is what a resume is verified against.

## Make it append-only

The table is append-only by intent, not by permission. Enforce it:

```sql
REVOKE UPDATE, DELETE ON side_effect_journal FROM sagacity_app;
GRANT  INSERT, SELECT ON side_effect_journal TO sagacity_app;
```

## Concurrency

Appends to one saga are serialized by the `(saga_id, seq)` primary key plus a
bounded retry. `SELECT ... FOR UPDATE` alone is not sufficient — it locks nothing
when the saga has no rows yet, so concurrent first-appends would all compute
`seq = 1` and all but one would fail. A losing append is retried against the
re-read tail rather than dropped, because a dropped `EXECUTED` row is an effect
compensation will never undo.
