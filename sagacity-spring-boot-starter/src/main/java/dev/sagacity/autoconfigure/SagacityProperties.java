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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isSchemaInit() { return schemaInit; }
    public void setSchemaInit(boolean schemaInit) { this.schemaInit = schemaInit; }

    public boolean isApprovalEndpointsEnabled() { return approvalEndpointsEnabled; }
    public void setApprovalEndpointsEnabled(boolean approvalEndpointsEnabled) { this.approvalEndpointsEnabled = approvalEndpointsEnabled; }
}
