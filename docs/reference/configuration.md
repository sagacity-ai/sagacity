# Configuration

All properties are under the `sagacity` prefix.

| Property | Default | Effect |
|---|---|---|
| `sagacity.enabled` | `true` | Master switch for auto-configuration. `false` wires nothing. |
| `sagacity.schema-init` | `true` | Create `side_effect_journal` on startup if absent. |
| `sagacity.approval-endpoints-enabled` | `true` | Expose the `/sagacity/**` REST endpoints. |

```yaml
sagacity:
  enabled: true
  schema-init: true
  approval-endpoints-enabled: true
```

## What auto-configuration decides

The starter picks a journal based on what is in the context:

| Condition | Journal |
|---|---|
| A `DataSource` bean exists | `PostgresSideEffectJournal` — hash-chained, durable |
| No `DataSource` | `InMemorySideEffectJournal` — **no hash chain, lost on restart** |

!!! danger "The fallback is silent"
    Missing the `DataSource` does not fail startup. You get a working system with
    no durability and no tamper evidence. Assert on it — see the
    [production checklist](../guides/production-checklist.md#verify-your-setup).

Every bean is `@ConditionalOnMissingBean`, so declaring your own wins:

```java
@Bean
SideEffectJournal sideEffectJournal(DataSource ds) {
    return new PostgresSideEffectJournal(ds);
}

@Bean
ApprovalStore approvalStore(JdbcTemplate jdbc) {
    return new MyDurableApprovalStore(jdbc);   // see the note below
}
```

!!! warning "The default approval store is in-memory"
    `InMemoryApprovalStore` is the default regardless of whether a `DataSource`
    exists. Pending approvals **do not survive a restart** — the journal keeps
    the `AWAITING_APPROVAL` record, but the request needed to resume is gone.
    Supply your own `ApprovalStore` bean if approvals must outlive a deploy.

## Schema

With `schema-init: true` the starter runs `CREATE TABLE IF NOT EXISTS`. To manage
it yourself (Flyway, Liquibase), set `schema-init: false` and apply
[the schema](journal-schema.md).

## Turning it off per environment

```yaml
# application-test.yml — no journaling, no endpoints
sagacity:
  enabled: false
```

`enabled: false` disables the **entire** auto-configuration — no `Sagacity`,
journal, approval store or controller bean is created. An application that
injects `Sagacity` will fail to start. Use it only where nothing depends on
Sagacity, or construct `Sagacity.create()` yourself.

To keep the library but drop only the HTTP surface, use
`approval-endpoints-enabled: false` instead.
