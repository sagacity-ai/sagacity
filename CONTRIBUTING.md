# Contributing to Sagacity

Thank you for your interest in contributing! Sagacity is in active early development and we welcome contributions.

## How to Contribute

1. **Check existing issues** — look for open issues or create a new one describing what you'd like to work on.
2. **Fork the repo** — create your own fork and work on a feature branch.
3. **Submit a PR** — with a clear description of what and why.

## Development Setup

```bash
# Clone
git clone https://github.com/sumitvairagar/sagacity.git
cd sagacity

# Build (requires Java 17+)
mvn clean install

# Run unit tests (fast, no Docker needed)
mvn test

# Run everything, including the real-Postgres integration tests
mvn verify
```

### Integration tests

`*IT` tests run under `mvn verify` and use Testcontainers to start a real
Postgres. They cover what the H2-based unit tests structurally cannot —
timestamp precision through a real round-trip, and concurrent appends.

**They need Docker running.** Without a reachable daemon Testcontainers
*skips* them rather than failing, so `mvn verify` still goes green while
testing nothing. If you are changing anything in `PostgresSideEffectJournal`
or `HashChain`, start Docker first and confirm the run reports
`Tests run: 11` for `PostgresSideEffectJournalIT`. CI enforces this and
fails the build if the ITs are skipped.

## Code Style

- Java 17+
- Follow existing patterns in the codebase
- Write tests for new functionality
- Keep commits focused and atomic

## Areas Where Help is Most Impactful

| Area | What's needed |
|------|---------------|
| **M1: Postgres journal** | Spring Data JDBC implementation of `SideEffectJournal` |
| **M1: Hash chain** | SHA-256 chain verification logic |
| **M2: Approval gates** | REST API design for human-in-the-loop approval |
| **Testing** | More failure scenario tests, concurrency tests |
| **Docs** | Usage guides, architecture diagrams, blog posts |

## Commit Messages

- Start with an uppercase verb
- Keep the title under 72 characters
- Reference issues: `Fixes #123`

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
