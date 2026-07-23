# Sagacity Documentation

## Guides

A from-scratch walkthrough of every layer in Sagacity — concepts, code, and reasoning.

| # | Guide | What you'll learn |
|---|-------|-------------------|
| 1 | [Project Structure](guide/01-project-structure.md) | Module layout, dependency boundaries, build config |
| 2 | [Annotations](guide/02-annotations.md) | @Compensable, @Compensation, Reversibility — the developer API |
| 3 | [The Journal](guide/03-journal.md) | SideEffectJournal, JournalEntry, Phase lifecycle |
| 4 | [Compensation](guide/04-compensation.md) | CompensationRunner — how undo works |
| 5 | [Persistence & Hash Chain](guide/05-persistence.md) | PostgresSideEffectJournal, SHA-256 tamper detection |
| 6 | [Spring AI Integration](guide/06-spring-ai-integration.md) | SagacityToolCallback, CompensationScanner, SagaScope |
| 7 | [The Facade](guide/07-facade.md) | Sagacity.java — tying it all together |
| 8 | [Approval Gates](guide/08-approval-gates.md) | Human-in-the-loop for irreversible actions |
| 9 | [Audit & Verification](guide/09-audit.md) | Export, chain verification, compliance |
| 10 | [Spring Boot Starter](guide/10-starter.md) | Auto-configuration, REST API, zero-config setup |

## Examples

| Example | Description |
|---------|-------------|
| [PlaceOrderDemo](../sagacity-examples/src/main/java/dev/sagacity/examples/PlaceOrderDemo.java) | Basic: 3 tools, step 3 fails, compensation runs |
| [ApprovalFlowDemo](../sagacity-examples/src/main/java/dev/sagacity/examples/ApprovalFlowDemo.java) | Irreversible tool suspended, approved via API |
| [AuditVerificationDemo](../sagacity-examples/src/main/java/dev/sagacity/examples/AuditVerificationDemo.java) | Export journal, verify hash chain integrity |

## Reference

- [Configuration Properties](reference/configuration.md)
- [REST API](reference/rest-api.md)
- [Database Schema](../sagacity-core/src/main/resources/sagacity-schema.sql)
