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

# Run tests
mvn test
```

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
