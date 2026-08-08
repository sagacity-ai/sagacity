# AGENTS.md

Guidance for AI coding agents working in this repository. Humans may find the
gotchas useful too.

## What this is

Sagacity adds SAGA-pattern compensation to Spring AI tool calls: declare an undo
per tool, journal every call to a hash-chained Postgres table, and gate
irreversible tools behind human approval.

This library sits in the execution path of real side effects — payments,
deletions — and produces the audit record of what happened. **A bug here means
money moves twice or an effect never gets undone.** Prefer failing loudly to
degrading quietly.

Published to Maven Central as `io.github.sumitvairagar:sagacity-*`.

## Build and test

```bash
mvn test      # unit tests only — fast, no Docker
mvn verify    # everything, including real-Postgres integration tests
```

**Always build the whole reactor.** `mvn test -pl sagacity-spring-boot-starter`
resolves `sagacity-core` from `~/.m2` — a published 0.1.0 jar — instead of your
working tree, so your changes are silently not tested. Use `-am` or no `-pl`.

**Integration tests need Docker.** Testcontainers *skips* `*IT` tests when no
daemon is reachable, so `mvn verify` still reports success while testing nothing.
CI fails the build if they were skipped. If you touch `PostgresSideEffectJournal`,
`PostgresApprovalStore` or `HashChain`, start Docker and confirm the run reports
`Tests run: 11` and `Tests run: 7` respectively.

## Architecture

Read [`docs/concepts/architecture.md`](docs/concepts/architecture.md) before
changing anything structural. Short version:

```
sagacity-core                 no Spring dependency — only javax.sql.DataSource
sagacity-spring-ai            Sagacity facade, SagacityToolCallback, SagaScope
sagacity-spring-boot-starter  auto-configuration + REST endpoints
sagacity-examples             demos, not published
sagacity-coverage             aggregate JaCoCo, not published
```

**Keep `sagacity-core` free of Spring.** That boundary is what makes a
LangChain4j adapter possible later. Do not import Spring into it.

## Documentation is part of the change

**If you change architecture, behaviour, or a public API, update the docs in the
same PR.** This is not a nicety — the previous docs described nine documents that
were never written, referenced a groupId that no longer existed, and documented
an approval flow that stopped halfway. Adopters copy-pasted things that could not
work.

| Change | Also update |
|---|---|
| Module layout, integration point, threading model | `docs/concepts/architecture.md` |
| Annotations, `Sagacity` API, REST endpoints, config properties | matching page under `docs/reference/` |
| A security property or a new limitation | `docs/concepts/threat-model.md` **and** `SECURITY.md` |
| Journal or approval table schema | `docs/reference/journal-schema.md`, `sagacity-schema.sql`, **and** the inline DDL in `SagacityAutoConfiguration.initSchema` — these three drift apart easily |
| A new sharp edge an operator must know | `docs/guides/production-checklist.md` |
| Anything shipped | `docs/about/roadmap.md` if it closes a roadmap item |

```bash
python3 -m venv .venv && .venv/bin/pip install -r docs/requirements.txt
.venv/bin/mkdocs serve
```

CI builds with `--strict`, so a broken internal link fails the PR.

Never document intended behaviour as though it exists. If it is not implemented,
it does not go in the docs.

## Non-obvious constraints

**Spring Boot 4.0.x is required, not preferred.** Spring AI 2.0.0 compiles
against Spring Framework 7. Boot 3.5.x resolves Spring Framework 6.2 and
downgrades `spring-core` underneath Spring AI, failing at runtime with
`NoClassDefFoundError: org/springframework/core/Nullness`. Versions come from the
`spring-boot-dependencies` BOM in the parent pom — do not pin them per module.

**A `@RestController` in this starter is not registered by being annotated.**
`dev.sagacity.autoconfigure` is not on a consuming application's component-scan
path. Controllers must be declared as `@Bean` in `SagacityAutoConfiguration` or
every endpoint 404s in a real app while MockMvc tests still pass.

**`CompensationRunner.compensate` is not idempotent.** It re-runs every
`EXECUTED` entry it finds. Call it exactly once per failure path. Calling it
twice — including once to run it and again while building a `SagaResult` — issues
two refunds.

**Never drop a journal append.** `append()` is called *after* the side effect has
run, so a lost `EXECUTED` row is an effect compensation will never undo. Fail
loudly instead.

**The approval path fails closed.** `resumeSaga` requires a pending request, a
journaled `APPROVED` decision, and a byte-exact payload hash match. Never add a
branch that proceeds on missing evidence — an approval with no recorded hash is
refused, not trusted.

**macOS is case-insensitive.** A case-only rename (`INDEX.md` → `index.md`) will
overwrite rather than rename, and it broke GitHub's squash-merge API twice. Use
`git mv` via a temporary name, and prefer renaming to a genuinely different name.

## Testing conventions

The promise is behaviour during failure, so failure-path coverage matters more
than happy-path coverage. Two rules paid for in real bugs:

**Count, do not flag.** `boolean compensated = true` cannot distinguish
"compensated" from "compensated twice", and double compensation is a double
refund. Use counters and assert exact numbers.

**Never swallow exceptions in test workers.** A `catch (Exception ignored)` in a
concurrency test hid 70 dropped journal appends behind a confusing size
assertion. Collect them and assert the list is empty.

**Prove the test catches the bug.** For a fix, verify the new test fails against
the unfixed code (`git stash` the main-code change, run, restore). A test that
passes both ways tests nothing.

**H2 is not Postgres.** `save()` in `PostgresApprovalStore` takes different SQL
per database (`MERGE` vs `ON CONFLICT`), and the journal's concurrency bug did not
reproduce on H2 at all. Storage behaviour needs an `*IT`.

## Code style

Match the file you are editing. Core and spring-ai modules use **tabs**; the
starter uses **4 spaces**. Java 17. Javadoc on public API explains *why*, not
*what* — the signature already says what.

Comments should explain reasoning that is not evident from the code. Do not
narrate.

## Releasing

See `docs/about/` and the `release` profile. Two traps:

- `maven.deploy.skip` does **not** exclude a module from the Central bundle. Use
  `<skipPublishing>true</skipPublishing>` — not `central.publishing.skipPublishing`,
  which parses fine and does nothing.
- Keep `autoPublish=false`, and check the bundle's `purls` via the Portal status
  API before releasing. Published coordinates can never be withdrawn.

## Never commit

`~/.m2/settings.xml` holds a Central token; the GPG passphrase lives in the macOS
Keychain. `docs/LAUNCH-POSTS.md` and `docs/SPRING-AI-COMMUNITY-PROPOSAL.md` are
gitignored private drafts — leave them alone, and keep them out of the MkDocs
build via `exclude_docs`.
