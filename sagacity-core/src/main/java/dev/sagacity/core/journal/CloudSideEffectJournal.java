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
 * <h2>Design constraints</h2>
 * <ul>
 *   <li>{@code sagacity-core} has zero framework dependencies — only the JDK.
 *       This class uses {@link HttpClient} from {@code java.net.http} (JDK 11+).
 *   <li>The hash chain is maintained server-side (the Cloud API owns ordering and
 *       hashing). Entries returned by {@link #entries} carry the hashes the server
 *       assigned; chain verification goes through the Cloud API's verify endpoint.
 *   <li>The API key is treated as a bearer token. It must never be logged.
 * </ul>
 *
 * <h2>Failure handling</h2>
 * <p>A failed append (non-2xx HTTP or network error) throws
 * {@link CloudJournalException}, which is an unchecked exception. The saga will
 * be in an indeterminate state — exactly as if the Postgres journal's connection
 * failed. Callers should treat this as fatal for the saga.
 *
 * <h2>Retries</h2>
 * <p>No automatic retries are attempted here. A retry on {@code append} is unsafe
 * without idempotency guarantees from the server side (the server must deduplicate
 * on {@code (sagaId, seq)} to make retries safe). When the Cloud API provides
 * idempotency keys this class will be updated to retry with a bounded back-off.
 *
 * @see SideEffectJournal
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

	/**
	 * Creates a journal pointing at the default Sagacity Cloud endpoint.
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
	 * @param baseUrl base URL without trailing slash, e.g. {@code https://api.sagacity.dev}
	 */
	public CloudSideEffectJournal(String apiKey, String baseUrl) {
		this(apiKey, baseUrl, HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build(),
				new CloudJournalSerializer());
	}

	/**
	 * Full constructor — package-private for testing (allows injecting a mock
	 * {@link HttpClient} and a custom serializer).
	 */
	CloudSideEffectJournal(String apiKey, String baseUrl, HttpClient http, CloudJournalSerializer serializer) {
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
	}

	/**
	 * Appends a journal entry to the Sagacity Cloud.
	 *
	 * <p>The server assigns the sequence number and computes the hash — these are
	 * returned in the response body and reflected in the returned {@link JournalEntry}.
	 *
	 * @throws CloudJournalException if the request fails or the server returns a
	 *                               non-2xx status
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

		HttpResponse<String> response = send(request);
		requireSuccessful(response, "append entry for saga " + sagaId);

		return serializer.deserializeEntry(response.body());
	}

	/**
	 * Retrieves all entries for a saga in append order.
	 *
	 * @throws CloudJournalException if the request fails or the server returns a
	 *                               non-2xx status
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

		HttpResponse<String> response = send(request);
		requireSuccessful(response, "fetch entries for saga " + sagaId);

		return serializer.deserializeEntries(response.body());
	}

	// ── internals ────────────────────────────────────────────────────────────

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException | InterruptedException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new CloudJournalException("Network error communicating with Sagacity Cloud", ex);
		}
	}

	private static void requireSuccessful(HttpResponse<String> response, String operation) {
		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			throw new CloudJournalException(
					"Sagacity Cloud returned HTTP " + status + " while trying to " + operation
					+ ". Body: " + truncate(response.body(), 200));
		}
	}

	/**
	 * Percent-encodes characters that are illegal or ambiguous in a URI path segment.
	 * Uses {@link java.net.URLEncoder} with UTF-8, then converts {@code +} back to
	 * {@code %20} (URLEncoder is form-encoding, not URI-encoding).
	 */
	private static String encode(String value) {
		try {
			return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
					.replace("+", "%20");
		} catch (Exception ex) {
			// StandardCharsets.UTF_8 never throws — this branch is unreachable
			return value;
		}
	}

	private static String truncate(String s, int max) {
		if (s == null) return "(null)";
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
