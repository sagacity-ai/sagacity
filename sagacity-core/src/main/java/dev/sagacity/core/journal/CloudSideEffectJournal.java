package dev.sagacity.core.journal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Cloud-backed journal that forwards every append to the Sagacity Cloud API and
 * reads entries back from it.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>{@code sagacity-core} has zero framework dependencies — only the JDK.
 *       This class uses {@link HttpClient} from {@code java.net.http} (JDK 11+).
 *   <li>The hash chain is maintained server-side. Entries returned by
 *       {@link #entries} carry the hashes the server assigned.
 *   <li>The API key is treated as a bearer token. It must never be logged.
 * </ul>
 *
 * <h2>Retry</h2>
 * <p>All HTTP calls go through {@link #sendWithRetry}, which retries on network
 * errors and 5xx/429 responses using exponential back-off. 4xx responses (except
 * 429) are not retried — a bad request or invalid API key will not be fixed by
 * retrying. See {@link CloudJournalRetryPolicy} for full retry semantics.
 *
 * <h2>Idempotency</h2>
 * <p>Retrying {@code append} is safe because the server enforces
 * {@code UNIQUE(team_id, saga_id, seq)}. A duplicate write returns the existing
 * entry rather than creating a second row.
 *
 * @see SideEffectJournal
 * @see CloudJournalRetryPolicy
 */
public final class CloudSideEffectJournal implements SideEffectJournal {

	/** Default base URL — overridable for self-hosted / staging deployments. */
	static final String DEFAULT_BASE_URL = "https://api.sagacity.dev";

	private static final String CONTENT_TYPE_JSON = "application/json";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient http;
	private final String baseUrl;
	private final String apiKey;
	private final CloudJournalSerializer serializer;
	private final CloudJournalRetryPolicy retryPolicy;

	// ── Constructors ──────────────────────────────────────────────────────────

	/**
	 * Creates a journal pointing at the default Sagacity Cloud endpoint,
	 * with the default retry policy (3 retries, exponential back-off from 200ms).
	 *
	 * @param apiKey the Bearer token issued by the Sagacity Cloud dashboard
	 */
	public CloudSideEffectJournal(String apiKey) {
		this(apiKey, DEFAULT_BASE_URL);
	}

	/**
	 * Creates a journal pointing at a custom endpoint (staging, self-hosted).
	 *
	 * @param apiKey  the Bearer token
	 * @param baseUrl base URL without trailing slash
	 */
	public CloudSideEffectJournal(String apiKey, String baseUrl) {
		this(apiKey, baseUrl,
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
				new CloudJournalSerializer(),
				CloudJournalRetryPolicy.DEFAULT);
	}

	/**
	 * Full constructor — package-private for testing.
	 * Allows injecting a stub {@link HttpClient}, serializer, and retry policy.
	 */
	CloudSideEffectJournal(String apiKey, String baseUrl, HttpClient http,
			CloudJournalSerializer serializer, CloudJournalRetryPolicy retryPolicy) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException("Sagacity Cloud API key must not be blank");
		}
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalArgumentException("Sagacity Cloud base URL must not be blank");
		}
		this.apiKey = apiKey;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.http = http;
		this.serializer = serializer;
		this.retryPolicy = retryPolicy;
	}

	// ── SideEffectJournal ─────────────────────────────────────────────────────

	/**
	 * Appends a journal entry to Sagacity Cloud.
	 * Retried automatically on transient failures — see {@link CloudJournalRetryPolicy}.
	 *
	 * @throws CloudJournalException if all retry attempts fail
	 */
	@Override
	public JournalEntry append(String sagaId, String toolName, Phase phase, String input, String payload) {
		String body = serializer.serializeAppendRequest(sagaId, toolName, phase, input, payload);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/v1/journal/entries"))
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", CONTENT_TYPE_JSON)
				.header("Accept", CONTENT_TYPE_JSON)
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		HttpResponse<String> response = sendWithRetry(request, "append entry for saga " + sagaId);
		return serializer.deserializeEntry(response.body());
	}

	/**
	 * Retrieves all entries for a saga in append order.
	 * Retried automatically on transient failures.
	 *
	 * @throws CloudJournalException if all retry attempts fail
	 */
	@Override
	public List<JournalEntry> entries(String sagaId) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/v1/journal/entries/" + encode(sagaId)))
				.header("Authorization", "Bearer " + apiKey)
				.header("Accept", CONTENT_TYPE_JSON)
				.timeout(REQUEST_TIMEOUT)
				.GET()
				.build();

		HttpResponse<String> response = sendWithRetry(request, "fetch entries for saga " + sagaId);
		return serializer.deserializeEntries(response.body());
	}

	// ── Retry logic ───────────────────────────────────────────────────────────

	/**
	 * Sends an HTTP request with automatic retry on transient failures.
	 *
	 * <p>The retry loop works as follows:
	 * <ol>
	 *   <li>Send the request.
	 *   <li>On success (2xx) — return the response immediately.
	 *   <li>On a retryable HTTP status (5xx, 429) or network error — sleep and retry.
	 *   <li>On a non-retryable status (4xx except 429) — throw immediately, no retry.
	 *   <li>If all attempts are exhausted — throw with the last failure as context.
	 * </ol>
	 *
	 * @param request   the request to send
	 * @param operation human-readable description used in error messages
	 * @return the successful HTTP response
	 * @throws CloudJournalException on permanent failure or exhausted retries
	 */
	private HttpResponse<String> sendWithRetry(HttpRequest request, String operation) {
		Exception lastException = null;
		int lastStatus = -1;

		for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
			// Sleep before every retry (never before the first attempt)
			sleepIfRetry(attempt, operation);

			try {
				HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
				int status = response.statusCode();

				if (isSuccess(status)) {
					return response;
				}

				if (retryPolicy.isRetryableStatus(status)) {
					// Transient server error — record and retry
					lastStatus = status;
					continue;
				}

				// Permanent HTTP error (4xx except 429) — throw immediately
				throw new CloudJournalException(
						"Sagacity Cloud returned HTTP " + status + " while trying to " + operation
						+ " (not retryable). Body: " + truncate(response.body(), 200));

			} catch (CloudJournalException ex) {
				throw ex; // already formatted — let it propagate

			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new CloudJournalException(
						"Interrupted while communicating with Sagacity Cloud during: " + operation, ex);

			} catch (IOException ex) {
				if (!retryPolicy.isRetryableException(ex)) {
					throw new CloudJournalException(
							"Non-retryable network error during: " + operation, ex);
				}
				lastException = ex;
			}
		}

		// All attempts exhausted — report the last failure
		String reason = lastException != null
				? lastException.getMessage()
				: "HTTP " + lastStatus;
		String message = "Failed to " + operation + " after " + retryPolicy.maxAttempts()
				+ " attempts. Last failure: " + reason;

		if (lastException != null) {
			throw new CloudJournalException(message, lastException);
		}
		throw new CloudJournalException(message);
	}

	/** Sleeps for the back-off duration before attempt {@code attemptNumber}. */
	private void sleepIfRetry(int attemptNumber, String operation) {
		Duration delay = retryPolicy.delayBefore(attemptNumber);
		if (delay.isZero()) return;
		try {
			Thread.sleep(delay.toMillis());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new CloudJournalException(
					"Interrupted during retry back-off for: " + operation, ex);
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private static boolean isSuccess(int httpStatus) {
		return httpStatus >= 200 && httpStatus < 300;
	}

	/**
	 * Percent-encodes characters that are illegal in a URI path segment.
	 * Uses {@link java.net.URLEncoder} with UTF-8, converting {@code +} to
	 * {@code %20} (URLEncoder uses form-encoding, not URI-encoding).
	 */
	private static String encode(String value) {
		try {
			return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
					.replace("+", "%20");
		} catch (Exception ex) {
			return value; // StandardCharsets.UTF_8 never throws — unreachable
		}
	}

	private static String truncate(String s, int max) {
		if (s == null) return "(null)";
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
