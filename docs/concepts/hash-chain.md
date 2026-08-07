# The hash chain

Each journal entry's hash covers the previous entry's hash, so editing any entry
invalidates every entry after it.

```
entry 1  hash = SHA-256( ZERO_HASH || fields of entry 1 )
entry 2  hash = SHA-256( hash₁     || fields of entry 2 )
entry 3  hash = SHA-256( hash₂     || fields of entry 3 )
```

Fields covered: previous hash, saga id, seq, tool name, phase, input, payload,
timestamp. The first entry of each saga chains from a zero hash.

## Why fields are length-prefixed

The obvious encoding — join the fields with a delimiter — is wrong here, and
subtly so.

`input` and `payload` hold arbitrary tool arguments and results. They can contain
any character, including whatever delimiter you picked. With a plain `|` join:

```
input = "a|b", payload = "c"   →  ...|a|b|c|...
input = "a",   payload = "b|c" →  ...|a|b|c|...
```

Two different entries, one preimage. An attacker who controls tool input could
craft an entry whose hash collides with a different, legitimate-looking one —
without breaking SHA-256 at all.

Sagacity prefixes every field with its UTF-8 byte length:

```
4:abcd 3:xyz 12:{"amount":1}
```

The encoding is now unambiguous: no arrangement of field contents can be parsed
two ways, so no arrangement can forge another entry's hash. Byte length rather
than character length, because multi-byte characters would otherwise reintroduce
the ambiguity — `"€uro"` and `"€"`/`"uro"` differ in bytes even where character
counts mislead.

## Why timestamps are truncated

Postgres `TIMESTAMP` holds microseconds. `Instant.now()` may carry nanoseconds
depending on platform. Hashing the nanosecond value and then storing a truncated
one means the value read back never rehashes to the stored hash — verification
would fail for **every persisted saga**, and would look exactly like tampering.

Timestamps are truncated to microseconds before hashing and before writing, and
rendered in a fixed-width canonical form rather than `Instant.toString()`, whose
output width varies with trailing-zero suppression.

## What SHA-256 is doing here

It provides **tamper evidence**, not tamper prevention. The chain makes it
infeasible to alter an entry and produce a matching hash without recomputing
everything downstream.

That defends against exactly one adversary: someone who can edit the database but
does not bother to — or cannot — recompute the chain. A careless insider, an
application-level SQL injection, a corrupted restore.

It does **not** defend against someone who can run Sagacity's own hashing over a
rewritten history. Nor does it detect truncation: deleting the last N entries
leaves a shorter, perfectly valid chain, because nothing inside a journal can
prove entries once existed.

Closing both gaps requires anchoring the head hash outside the database — see the
[threat model](threat-model.md).

## Would a stronger algorithm help?

No. The weaknesses above are structural, not cryptographic. SHA-256 has no known
practical collision or preimage attack, and swapping it for SHA-3 or BLAKE3 would
change nothing about truncation or wholesale rewriting. Effort is better spent on
anchoring and on `REVOKE UPDATE, DELETE`.
