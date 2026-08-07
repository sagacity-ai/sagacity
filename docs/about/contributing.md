# Contributing

The full guide lives in
[CONTRIBUTING.md](https://github.com/sumitvairagar/sagacity/blob/main/CONTRIBUTING.md).
The short version:

```bash
git clone https://github.com/sumitvairagar/sagacity.git
cd sagacity
mvn test      # unit tests, fast, no Docker
mvn verify    # everything, including real-Postgres integration tests
```

## Integration tests need Docker

`*IT` tests use Testcontainers to start a real Postgres. Without a reachable
Docker daemon Testcontainers **skips** them rather than failing, so `mvn verify`
still goes green while testing nothing.

If you touch `PostgresSideEffectJournal` or `HashChain`, start Docker and confirm
the run reports `Tests run: 11` for `PostgresSideEffectJournalIT`. CI fails the
build if the ITs were skipped.

## What good tests look like here

The library's promise is behaviour during failure, so failure-path coverage
matters more than happy-path coverage. Two lessons paid for in bugs:

**Count, do not flag.** A boolean `compensated = true` cannot distinguish
"compensated" from "compensated twice" — and double compensation means a double
refund. Use a counter and assert the exact number.

**Never swallow exceptions in test workers.** A `catch (Exception ignored)` in a
concurrency test hid 70 dropped journal appends behind a confusing size
assertion. Collect them and assert they are empty.

## Working on the docs

```bash
pip install mkdocs-material
mkdocs serve      # http://localhost:8000
```

Docs follow [Diátaxis](https://diataxis.fr): getting-started teaches, guides
solve a task, reference describes, concepts explain why. Keeping those separate
is what stops a reference page from turning into a tutorial.
