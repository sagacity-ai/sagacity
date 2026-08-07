# The saga model

## The problem

An agent performs a sequence of steps with real side effects. Step 4 fails. Steps
1–3 already happened, in production, to real systems.

Retrying does not help — the card is already charged. Rolling back does not exist
— there is no database transaction spanning Stripe, your warehouse, and an email
provider. This is the situation the saga pattern was described for in 1987, long
before agents: a long-lived operation that cannot hold a lock, decomposed into
steps each of which has a **compensating action**.

## What a saga is here

A saga is the transactional scope of one agent task. It has an id, a journal, and
an outcome.

```
saga "order-123"
├── reserveInventory  → compensation: releaseInventory
├── chargeCard        → compensation: refundCharge
└── dispatchOrder     → no compensation declared
```

If anything fails, Sagacity walks the journal backward and runs each declared
compensation in reverse order of execution.

## Compensation is not rollback

| Rollback | Compensation |
|---|---|
| Erases the effect | Adds a corrective effect |
| Requires a transaction | Requires a declared inverse |
| Nobody observed the change | Someone may have already observed it |
| Database guarantees it | You write it |

A refund is not "the charge never happened". The charge is in the customer's
statement and the refund appears beside it. Both are real. That distinction is
why `Reversibility` exists as a vocabulary: `REVERSIBLE` claims erasure,
`COMPENSATABLE` admits the correction is a new fact, and `IRREVERSIBLE` admits
there is no correction at all.

## Why the journal comes first

Every effect is journaled `INTENT` **before** execution and `EXECUTED` after:

```
INTENT    chargeCard  {"amount":"100"}     ← written before the call
EXECUTED  chargeCard  ch_1M2n3             ← written after it returns
```

If the process dies between the two, the record shows an intent with no outcome.
That is not a gap — it is the most important signal the journal produces. It says
"an effect may exist that nothing knows the result of", which is precisely the
state a human needs to investigate.

Compensation only walks `EXECUTED` entries. A tool that threw did not necessarily
complete its effect, so Sagacity does not assume it needs undoing.

## Where this sits relative to durable execution

Temporal, Restate and DBOS solve **durability**: the workflow survives a crash
and resumes where it left off. That is genuinely hard and Sagacity does not
attempt it.

Durability and compensation are orthogonal. A durable workflow that resumes
perfectly still leaves you with a charged card when the business logic says the
order must be abandoned. Something has to issue the refund, and something has to
record that it did.

Sagacity can run inside a durable workflow. It is a decorator on Spring AI's
tool-calling path, not an execution engine, and it holds no scheduler, no
queue and no state machine beyond the journal.

## Limits of the model

- **An effect the journal never recorded cannot be compensated.** The window
  between a tool returning and its `EXECUTED` row committing is small but real.
- **Compensation ordering assumes independence.** Sagacity reverses execution
  order; it does not understand that releasing inventory before refunding might
  matter to you. If ordering constraints exist beyond reversal, encode them in
  the compensations themselves.
- **No distributed consensus.** There is no two-phase commit, no exactly-once
  delivery across systems, and no coordination between concurrent sagas touching
  the same resource.
