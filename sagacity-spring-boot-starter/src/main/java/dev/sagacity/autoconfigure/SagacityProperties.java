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

	/** Cloud journal configuration. Activates when {@code sagacity.cloud.api-key} is set. */
	private Cloud cloud = new Cloud();

	/** Global retry backoff configuration. Per-tool retries are declared via {@code @Compensable}. */
	private Retry retry = new Retry();

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }

	public boolean isSchemaInit() { return schemaInit; }
	public void setSchemaInit(boolean schemaInit) { this.schemaInit = schemaInit; }

	public boolean isApprovalEndpointsEnabled() { return approvalEndpointsEnabled; }
	public void setApprovalEndpointsEnabled(boolean approvalEndpointsEnabled) { this.approvalEndpointsEnabled = approvalEndpointsEnabled; }

	public Cloud getCloud() { return cloud; }
	public void setCloud(Cloud cloud) { this.cloud = cloud; }

	public Retry getRetry() { return retry; }
	public void setRetry(Retry retry) { this.retry = retry; }

	/**
	 * Global retry backoff configuration.
	 *
	 * <p>Per-tool retry counts and exception whitelists are declared on
	 * {@code @Compensable(retries=3, retryOn={...})}. This section controls
	 * the backoff timing shared across all retrying tools.
	 *
	 * <pre>
	 * sagacity:
	 *   retry:
	 *     initial-delay-ms: 100      # default
	 *     backoff-multiplier: 2.0    # default — exponential: 100ms, 200ms, 400ms
	 * </pre>
	 */
	public static class Retry {

		/** Initial backoff delay in milliseconds before the first retry. */
		private long initialDelayMs = 100L;

		/**
		 * Exponential backoff multiplier applied per retry.
		 * Must be >= 1.0. Default 2.0 gives: 100ms, 200ms, 400ms, 800ms...
		 */
		private double backoffMultiplier = 2.0;

		public long getInitialDelayMs() { return initialDelayMs; }
		public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }

		public double getBackoffMultiplier() { return backoffMultiplier; }
		public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
	}

	/**
	 * Sagacity Cloud journal configuration.
	 *
	 * <p>When {@code sagacity.cloud.api-key} is set, the Cloud journal takes
	 * priority over the local Postgres journal. The local {@code DataSource}, if
	 * present, is still used for the {@code ApprovalStore} unless overridden.
	 *
	 * <pre>
	 * sagacity:
	 *   cloud:
	 *     api-key: ${SAGACITY_CLOUD_API_KEY}
	 *     # base-url: https://api.sagacity.dev   # default, override for self-hosted
	 * </pre>
	 */
	public static class Cloud {

		/**
		 * Bearer token issued by the Sagacity Cloud dashboard.
		 * When blank, the Cloud journal is not used.
		 */
		private String apiKey;

		/**
		 * Base URL of the Sagacity Cloud API.
		 * Defaults to {@code https://api.sagacity.dev}.
		 * Override for self-hosted deployments or staging.
		 */
		private String baseUrl;

		public String getApiKey() { return apiKey; }
		public void setApiKey(String apiKey) { this.apiKey = apiKey; }

		public String getBaseUrl() { return baseUrl; }
		public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

		/** Returns true if a Cloud API key has been configured. */
		public boolean isConfigured() {
			return apiKey != null && !apiKey.isBlank();
		}
	}
}
