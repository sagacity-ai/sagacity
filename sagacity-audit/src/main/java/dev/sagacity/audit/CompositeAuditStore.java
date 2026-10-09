package dev.sagacity.audit;

import java.util.List;

/**
 * {@link AuditStore} that fans out every append to multiple delegate stores.
 *
 * <p>The first delegate in the list is the <em>primary</em> store — its returned
 * {@link AuditEntry} (with the assigned {@code seq} and {@code hash}) is
 * authoritative and is passed to subsequent delegates. This ensures all stores
 * record the same seq numbers and hash chain, keeping them in sync.
 *
 * <p>Read operations ({@link #findBySagaId}) are served by the primary store only.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AuditStore store = CompositeAuditStore.of(
 *     new JdbcAuditStore(dataSource),   // primary: durable, hash-chained
 *     new Slf4jAuditStore()             // secondary: human-readable log output
 * );
 * }</pre>
 *
 * <h2>Failure semantics</h2>
 * <p>If a secondary delegate's {@code append} throws, the exception is logged
 * and swallowed so the primary write is not rolled back. A failed secondary
 * is treated as best-effort. If the primary throws, the exception propagates
 * normally.
 */
public final class CompositeAuditStore implements AuditStore {

    private final List<AuditStore> delegates;

    private CompositeAuditStore(List<AuditStore> delegates) {
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("CompositeAuditStore requires at least one delegate");
        }
        this.delegates = List.copyOf(delegates);
    }

    /**
     * Creates a {@link CompositeAuditStore} from the given stores.
     * The first store is the primary — its seq and hash are authoritative.
     *
     * @param stores at least one store; first is primary
     * @return composite store
     */
    public static CompositeAuditStore of(AuditStore... stores) {
        return new CompositeAuditStore(List.of(stores));
    }

    /**
     * Appends to the primary store, then propagates the returned entry
     * (with assigned seq and hash) to all secondary stores.
     */
    @Override
    public AuditEntry append(String sagaId, String toolName, Phase phase, String input) {
        // Primary — authoritative for seq and hash
        AuditEntry canonical = delegates.get(0).append(sagaId, toolName, phase, input);

        // Secondaries — receive the canonical entry; best-effort
        for (int i = 1; i < delegates.size(); i++) {
            AuditStore secondary = delegates.get(i);
            try {
                secondary.append(sagaId, toolName, phase, input);
            } catch (Exception ex) {
                // Best-effort: log and continue. Primary write is already durable.
                java.util.logging.Logger.getLogger(CompositeAuditStore.class.getName())
                        .warning(String.format(
                                "[SAGACITY] Secondary AuditStore %s failed sagaId=%s seq=%d: %s",
                                secondary.getClass().getSimpleName(), sagaId,
                                canonical.seq(), ex.getMessage()));
            }
        }

        return canonical;
    }

    /** Reads from the primary store only. */
    @Override
    public List<AuditEntry> findBySagaId(String sagaId) {
        return delegates.get(0).findBySagaId(sagaId);
    }

    /** Reads from the primary store only. */
    @Override
    public List<AuditEntry> findBySagaId(String sagaId, Class<? extends Phase> phaseType) {
        return delegates.get(0).findBySagaId(sagaId, phaseType);
    }
}
