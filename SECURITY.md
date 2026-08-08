# Security Policy

Sagacity sits in the execution path of real side effects — payments, deletions,
messages — and produces the audit record of what happened. Security reports are
taken seriously here.

## Reporting a vulnerability

**Do not open a public issue.**

Use GitHub's private reporting:
[Report a vulnerability](https://github.com/sumitvairagar/sagacity/security/advisories/new)

If that is unavailable, email **sumit.vairagar@gmail.com** with `SAGACITY SECURITY`
in the subject.

Please include what you can: affected version, a description of the issue, steps
or a test that demonstrates it, and what an attacker gains.

### What to expect

| | |
|---|---|
| Acknowledgement | within 5 working days |
| Initial assessment | within 10 working days |
| Fix or documented mitigation | depends on severity; you will be kept informed |

This is currently a single-maintainer project, so these are honest targets rather
than a staffed SLA. If you have not heard back in two weeks, please ping the
thread — it means something went wrong, not that the report was dismissed.

Credit is given in the release notes unless you prefer otherwise.

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x | ✅ |
| < 0.1.0 | ❌ |

Pre-1.0, fixes land on the latest minor only. There are no backports.

## Scope

### In scope

Anything that lets an attacker:

- execute an `IRREVERSIBLE` tool without a valid, matching approval
- alter or forge journal entries in a way chain verification does not detect
- cause a side effect to execute without being journaled, or a journaled effect
  to escape compensation
- bypass payload-hash binding between approval and execution
- extract data through the audit export or approval endpoints beyond what the
  caller should see

### Known limitations, not vulnerabilities

These are documented design limits of `0.1.x`. Reports about them are welcome as
**issues**, and especially as pull requests, but they are not treated as
vulnerabilities because they are stated behaviour:

- **The approval REST endpoints are unauthenticated by default.** Securing them
  is the adopting application's responsibility. See the
  [production checklist](https://sumitvairagar.github.io/sagacity/guides/production-checklist/).
- **Approver identity is not verified.** Sagacity records the `approver` field
  verbatim. Pass an authenticated principal from your own security context.
- **Pending approvals require a `DataSource` to survive a restart.** Configure
  one and `PostgresApprovalStore` is selected automatically; without one the
  in-memory fallback loses them.
- **The hash chain does not detect tail truncation**, and does not defend against
  an adversary who can rewrite the whole chain with the same hashing. Mitigate by
  anchoring the head hash outside the database.
- **A crash between a tool executing and its `EXECUTED` row committing** leaves an
  effect that compensation will not undo. The preceding `INTENT` row makes this
  visible; sweeping for it is the operator's job.

The full analysis is in the
[threat model](https://sumitvairagar.github.io/sagacity/concepts/threat-model/).

## Security-relevant design

For reviewers, these are the parts worth attacking first:

- **Approval-to-payload binding** — `Sagacity.resumeSaga` verifies that a pending
  request exists, that an `APPROVED` decision was journaled for it, and that
  `SHA-256(livePayload)` equals the hash recorded when approval was requested.
  All three fail closed.
- **Hash chain canonicalization** — `HashChain.computeHash` length-prefixes every
  field with its UTF-8 byte length, so field contents cannot forge a boundary and
  collide with a different entry.
- **Journal append under concurrency** — `PostgresSideEffectJournal` retries on
  unique-key violation rather than dropping the append, because a dropped
  `EXECUTED` row is an effect that never gets compensated.

## Dependencies

Sagacity depends on Spring AI and Spring Boot. Vulnerabilities in those should be
reported to their respective projects. If a Spring vulnerability is exploitable
*specifically* through the way Sagacity uses it, report it here as well.
