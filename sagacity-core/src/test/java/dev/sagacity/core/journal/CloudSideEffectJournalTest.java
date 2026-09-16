package dev.sagacity.core.journal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link CloudSideEffectJournal} against a lightweight stub HTTP server
 * (com.sun.net.httpserver — JDK built-in, no extra dependency).
 *
 * <p>The {@link StubHandler} supports both single-response and multi-response
 * (queue-based) modes so retry behaviour can be tested by returning different
 * HTTP statuses for successive requests to the same endpoint.
 */
class CloudSideEffectJournalTest {

	private HttpServer server;
	private String baseUrl;
	private StubHandler handler;

	// A no-delay retry policy used in tests that need fast execution
	private static final CloudJournalRetryPolicy NO_DELAY_RETRY =
			new CloudJournalRetryPolicy(4, Duration.ZERO);

	@BeforeEach
	void startServer() throws IOException {
		handler = new StubHandler();
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", handler);
		server.start();
		baseUrl = "http://localhost:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	// ── Helper: build journal with no-delay retry for tests ──────────────────

	private CloudSideEffectJournal journal(String apiKey) {
		return new CloudSideEffectJournal(apiKey, baseUrl,
				HttpClient.newBuilder().build(),
				new CloudJournalSerializer(),
				NO_DELAY_RETRY);
	}

	// ── append — happy path ───────────────────────────────────────────────────

	@Test
	void append_sendsCorrectRequestAndReturnsEntry() {
		handler.respondWith(201, entryJson("saga-1", 1, "reserveInventory", "INTENT", "{\"qty\":5}", "", "2026-09-15T10:00:00Z", "hash-abc"));

		JournalEntry entry = journal("test-key").append("saga-1", "reserveInventory", Phase.INTENT, "{\"qty\":5}", "");

		assertThat(handler.lastMethod()).isEqualTo("POST");
		assertThat(handler.lastPath()).isEqualTo("/v1/journal/entries");
		assertThat(handler.lastAuthHeader()).isEqualTo("Bearer test-key");
		assertThat(handler.lastBody()).contains("\"sagaId\":\"saga-1\"");
		assertThat(handler.lastBody()).contains("\"phase\":\"INTENT\"");
		assertThat(entry.sagaId()).isEqualTo("saga-1");
		assertThat(entry.seq()).isEqualTo(1);
		assertThat(entry.hash()).isEqualTo("hash-abc");
	}

	@Test
	void append_includesContentTypeHeader() {
		handler.respondWith(201, entryJson("s", 1, "t", "INTENT", "", "", "2026-01-01T00:00:00Z", "h"));

		journal("key").append("s", "t", Phase.INTENT, "", "");

		assertThat(handler.lastContentTypeHeader()).isEqualTo("application/json");
	}

	// ── append — error handling ───────────────────────────────────────────────

	@Test
	void append_throwsImmediatelyOn401_noRetry() {
		handler.respondWith(401, "{\"error\":\"unauthorized\"}");
		AtomicInteger requestCount = handler.countRequests();

		assertThatThrownBy(() -> journal("bad-key").append("s", "t", Phase.INTENT, "", ""))
				.isInstanceOf(CloudJournalException.class)
				.hasMessageContaining("HTTP 401")
				.hasMessageContaining("not retryable");

		// 401 is a permanent failure — should not retry
		assertThat(requestCount.get()).isEqualTo(1);
	}

	@Test
	void append_throwsImmediatelyOn400_noRetry() {
		handler.respondWith(400, "{\"error\":\"bad request\"}");
		AtomicInteger requestCount = handler.countRequests();

		assertThatThrownBy(() -> journal("key").append("s", "t", Phase.INTENT, "", ""))
				.isInstanceOf(CloudJournalException.class)
				.hasMessageContaining("HTTP 400");

		assertThat(requestCount.get()).isEqualTo(1);
	}

	// ── append — retry on transient failures ─────────────────────────────────

	@Test
	void append_retriesOn503_andSucceedsOnRetry() {
		String successBody = entryJson("s", 1, "t", "INTENT", "", "", "2026-01-01T00:00:00Z", "h");
		// First call fails with 503, second succeeds
		handler.respondWithSequence(
				new StubResponse(503, "{\"error\":\"overloaded\"}"),
				new StubResponse(201, successBody)
		);

		JournalEntry entry = journal("key").append("s", "t", Phase.INTENT, "", "");

		assertThat(entry.seq()).isEqualTo(1);
		assertThat(handler.totalRequestCount()).isEqualTo(2);
	}

	@Test
	void append_retriesOn500_exhaustsAllAttemptsAndThrows() {
		// All 4 attempts return 500
		handler.alwaysRespondWith(500, "{\"error\":\"server error\"}");

		assertThatThrownBy(() -> journal("key").append("s", "t", Phase.INTENT, "", ""))
				.isInstanceOf(CloudJournalException.class)
				.hasMessageContaining("4 attempts");

		assertThat(handler.totalRequestCount()).isEqualTo(4); // maxAttempts = 4
	}

	@Test
	void append_retriesOn429_andSucceedsOnRetry() {
		String successBody = entryJson("s", 1, "t", "INTENT", "", "", "2026-01-01T00:00:00Z", "h");
		handler.respondWithSequence(
				new StubResponse(429, "{\"error\":\"rate limited\"}"),
				new StubResponse(201, successBody)
		);

		JournalEntry entry = journal("key").append("s", "t", Phase.INTENT, "", "");

		assertThat(entry.seq()).isEqualTo(1);
		assertThat(handler.totalRequestCount()).isEqualTo(2);
	}

	@Test
	void append_succeedsAfterTwoTransientFailures() {
		String successBody = entryJson("s", 1, "t", "INTENT", "", "", "2026-01-01T00:00:00Z", "h");
		handler.respondWithSequence(
				new StubResponse(503, ""),
				new StubResponse(500, ""),
				new StubResponse(201, successBody)
		);

		JournalEntry entry = journal("key").append("s", "t", Phase.INTENT, "", "");

		assertThat(entry.seq()).isEqualTo(1);
		assertThat(handler.totalRequestCount()).isEqualTo(3);
	}

	// ── entries — happy path ──────────────────────────────────────────────────

	@Test
	void entries_sendsGetRequestAndReturnsEntries() {
		String e1 = entryJson("saga-1", 1, "tool", "INTENT", "in", "", "2026-01-01T00:00:00Z", "h1");
		String e2 = entryJson("saga-1", 2, "tool", "EXECUTED", "in", "out", "2026-01-01T00:00:01Z", "h2");
		handler.respondWith(200, "{\"entries\":[" + e1 + "," + e2 + "]}");

		List<JournalEntry> entries = journal("test-key").entries("saga-1");

		assertThat(handler.lastMethod()).isEqualTo("GET");
		assertThat(handler.lastPath()).isEqualTo("/v1/journal/entries/saga-1");
		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).phase()).isEqualTo(Phase.INTENT);
		assertThat(entries.get(1).phase()).isEqualTo(Phase.EXECUTED);
	}

	@Test
	void entries_returnsEmptyListWhenNoEntries() {
		handler.respondWith(200, "{\"entries\":[]}");

		assertThat(journal("key").entries("empty-saga")).isEmpty();
	}

	@Test
	void entries_encodesSpecialCharactersInSagaId() {
		handler.respondWith(200, "{\"entries\":[]}");

		journal("key").entries("saga/with/slashes");

		assertThat(handler.lastPath()).isEqualTo("/v1/journal/entries/saga%2Fwith%2Fslashes");
	}

	@Test
	void entries_retriesOn503_andSucceeds() {
		handler.respondWithSequence(
				new StubResponse(503, ""),
				new StubResponse(200, "{\"entries\":[]}")
		);

		List<JournalEntry> entries = journal("key").entries("saga-1");

		assertThat(entries).isEmpty();
		assertThat(handler.totalRequestCount()).isEqualTo(2);
	}

	// ── constructor validation ────────────────────────────────────────────────

	@Test
	void constructor_throwsOnBlankApiKey() {
		assertThatThrownBy(() -> new CloudSideEffectJournal(""))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("API key");
	}

	@Test
	void constructor_throwsOnNullApiKey() {
		assertThatThrownBy(() -> new CloudSideEffectJournal(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void constructor_stripsTrailingSlashFromBaseUrl() {
		handler.respondWith(200, "{\"entries\":[]}");

		new CloudSideEffectJournal("key", baseUrl + "/",
				HttpClient.newBuilder().build(),
				new CloudJournalSerializer(),
				NO_DELAY_RETRY).entries("saga-1");

		assertThat(handler.lastPath()).isEqualTo("/v1/journal/entries/saga-1");
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private static String entryJson(String sagaId, long seq, String toolName, String phase,
			String input, String payload, String timestamp, String hash) {
		return String.format(
				"{\"sagaId\":\"%s\",\"seq\":%d,\"toolName\":\"%s\",\"phase\":\"%s\"," +
				"\"input\":\"%s\",\"payload\":\"%s\",\"timestamp\":\"%s\",\"hash\":\"%s\"}",
				sagaId, seq, toolName, phase, input, payload, timestamp, hash);
	}

	// ── Stub handler ──────────────────────────────────────────────────────────

	record StubResponse(int status, String body) {}

	private static class StubHandler implements HttpHandler {

		private volatile int defaultStatus = 200;
		private volatile String defaultBody = "";
		private final Queue<StubResponse> responseQueue = new ConcurrentLinkedQueue<>();
		private final AtomicInteger totalRequests = new AtomicInteger(0);

		private final AtomicReference<String> lastMethod = new AtomicReference<>();
		private final AtomicReference<String> lastPath = new AtomicReference<>();
		private final AtomicReference<String> lastAuth = new AtomicReference<>();
		private final AtomicReference<String> lastContentType = new AtomicReference<>();
		private final AtomicReference<String> lastBody = new AtomicReference<>();

		void respondWith(int status, String body) {
			this.defaultStatus = status;
			this.defaultBody = body;
			this.responseQueue.clear();
		}

		void alwaysRespondWith(int status, String body) {
			respondWith(status, body);
		}

		void respondWithSequence(StubResponse... responses) {
			responseQueue.clear();
			for (StubResponse r : responses) responseQueue.add(r);
		}

		AtomicInteger countRequests() { return totalRequests; }
		int totalRequestCount() { return totalRequests.get(); }
		String lastMethod() { return lastMethod.get(); }
		String lastPath() { return lastPath.get(); }
		String lastAuthHeader() { return lastAuth.get(); }
		String lastContentTypeHeader() { return lastContentType.get(); }
		String lastBody() { return lastBody.get(); }

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			totalRequests.incrementAndGet();
			lastMethod.set(exchange.getRequestMethod());
			lastPath.set(exchange.getRequestURI().getRawPath());
			lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			lastBody.set(new String(exchange.getRequestBody().readAllBytes()));

			StubResponse next = responseQueue.poll();
			int status = next != null ? next.status() : defaultStatus;
			String body = next != null ? next.body() : defaultBody;

			byte[] bytes = body.getBytes();
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
	}
}
