package dev.sagacity.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Sagacity.
 *
 * @see SagacityAutoConfiguration
 */
@ConfigurationProperties(prefix = "sagacity")
public class SagacityProperties {

    /** Whether Sagacity auto-configuration is enabled. */
    private boolean enabled = true;

    /** Whether to auto-initialize the database schema on startup. */
    private boolean schemaInit = true;

    /** Whether the approval REST endpoints are exposed. */
    private boolean approvalEndpointsEnabled = true;

    /**
     * Whether the embedded UI is served at {@code /sagacity/ui}.
     * Enabled by default. Disable with {@code sagacity.ui-enabled=false}.
     */
    private boolean uiEnabled = true;

    /** Global retry backoff configuration. Per-tool retries are declared via {@code @Compensable}. */
    private Retry retry = new Retry();

    /**
     * AuditStore backend selection. Valid values:
     * <ul>
     *   <li>{@code jdbc} (default) — durable JDBC store with hash chain
     *   <li>{@code slf4j} — zero-infrastructure logging store (dev/eval only)
     *   <li>{@code memory} — in-memory store (test only, no persistence)
     * </ul>
     */
    private String auditStore = "jdbc";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isSchemaInit() { return schemaInit; }
    public void setSchemaInit(boolean schemaInit) { this.schemaInit = schemaInit; }

    public boolean isApprovalEndpointsEnabled() { return approvalEndpointsEnabled; }
    public void setApprovalEndpointsEnabled(boolean v) { this.approvalEndpointsEnabled = v; }

    public boolean isUiEnabled() { return uiEnabled; }
    public void setUiEnabled(boolean uiEnabled) { this.uiEnabled = uiEnabled; }

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public String getAuditStore() { return auditStore; }
    public void setAuditStore(String auditStore) { this.auditStore = auditStore; }

    /**
     * Global retry backoff configuration.
     *
     * <pre>
     * sagacity:
     *   retry:
     *     initial-delay-ms: 100
     *     backoff-multiplier: 2.0
     * </pre>
     */
    public static class Retry {

        private long initialDelayMs = 100L;
        private double backoffMultiplier = 2.0;

        public long getInitialDelayMs() { return initialDelayMs; }
        public void setInitialDelayMs(long v) { this.initialDelayMs = v; }

        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double v) { this.backoffMultiplier = v; }
    }
}
