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

| Condition | Journal | Approval store |
|---|---|---|
| A `DataSource` bean exists | `PostgresSideEffectJournal` — hash-chained, durable | `PostgresApprovalStore` — durable |
| No `DataSource` | `InMemorySideEffectJournal` — **no hash chain, lost on restart** | `InMemoryApprovalStore` — **lost on restart** |

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

!!! warning "Without a DataSource, pending approvals die on restart"
    `InMemoryApprovalStore` is the fallback when no `DataSource` is present. The
    journal keeps the `AWAITING_APPROVAL` record, but the request holding the
    approved payload hash is gone — so the saga looks like it is waiting for a
    human forever and the tool can never be resumed. A deploy during business
    hours would strand every in-flight approval.

    With a `DataSource`, `PostgresApprovalStore` is selected automatically and
    pending approvals survive restarts.

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
