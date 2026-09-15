package dev.sagacity.core.journal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * (com.sun.net.httpserver — always available on the JDK, no extra dependency).
 *
 * <p>This avoids Mockito (not on the classpath) and stays consistent with the
 * rest of sagacity-core's approach: H2 for Postgres tests, JDK internals here.
 */
class CloudSideEffectJournalTest {

	private HttpServer server;
	private String baseUrl;
	private StubHandler handler;

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

	// ── append ────────────────────────────────────────────────────────────────

	@Test
	void append_sendsCorrectRequestAndReturnsEntry() {
		String responseBody = entryJson("saga-1", 1, "reserveInventory", "INTENT", "{\"qty\":5}", "", "2026-09-15T10:00:00Z", "hash-abc");
		handler.respondWith(201, responseBody);

		CloudSideEffectJournal journal = new CloudSideEffectJournal("test-key", baseUrl);
		JournalEntry entry = journal.append("saga-1", "reserveInventory", Phase.INTENT, "{\"qty\":5}", "");

		// Verify what was sent
		assertThat(handler.lastMethod()).isEqualTo("POST");
		assertThat(handler.lastPath()).isEqualTo("/v1/journal/entries");
		assertThat(handler.lastAuthHeader()).isEqualTo("Bearer test-key");
		assertThat(handler.lastBody()).contains("\"sagaId\":\"saga-1\"");
		assertThat(handler.lastBody()).contains("\"phase\":\"INTENT\"");

		// Verify what was returned
		assertThat(entry.sagaId()).isEqualTo("saga-1");
		assertThat(entry.seq()).isEqualTo(1);
		assertThat(entry.toolName()).isEqualTo("reserveInventory");
		assertThat(entry.phase()).isEqualTo(Phase.INTENT);
		assertThat(entry.hash()).isEqualTo("hash-abc");
	}

	@Test
	void append_throwsCloudJournalExceptionOnNon2xxResponse() {
		handler.respondWith(500, "{\"error\":\"internal server error\"}");

		CloudSideEffectJournal journal = new CloudSideEffectJournal("key", baseUrl);

		assertThatThrownBy(() -> journal.append("saga-1", "tool", Phase.INTENT, "", ""))
				.isInstanceOf(CloudJournalException.class)
				.hasMessageContaining("HTTP 500");
	}

	@Test
	void append_throwsCloudJournalExceptionOn401() {
		handler.respondWith(401, "{\"error\":\"unauthorized\"}");

		CloudSideEffectJournal journal = new CloudSideEffectJournal("bad-key", baseUrl);

		assertThatThrownBy(() -> journal.append("saga-1", "tool", Phase.INTENT, "", ""))
				.isInstanceOf(CloudJournalException.class)
				.hasMessageContaining("HTTP 401");
	}

	@Test
	void append_includesContentTypeHeader() {
		handler.respondWith(201, entryJson("s", 1, "t", "INTENT", "", "", "2026-01-01T00:00:00Z", "h"));

		new CloudSideEffectJournal("key", baseUrl).append("s", "t", Phase.INTENT, "", "");

		assertThat(handler.lastContentTypeHeader()).isEqualTo("application/json");
	}

	// ── entries ───────────────────────────────────────────────────────────────

	@Test
	void entries_sendsGetRequestAndReturnsEntries() {
		String e1 = entryJson("saga-1", 1, "tool", "INTENT", "in", "", "2026-01-01T00:00:00Z", "h1");
		String e2 = entryJson("saga-1", 2, "tool", "EXECUTED", "in", "out", "2026-01-01T00:00:01Z", "h2");
		handler.respondWith(200, "{\"entries\":[" + e1 + "," + e2 + "]}");

		CloudSideEffectJournal journal = new CloudSideEffectJournal("test-key", baseUrl);
		List<JournalEntry> entries = journal.entries("saga-1");

		assertThat(handler.lastMethod()).isEqualTo("GET");
		assertThat(handler.lastPath()).isEqualTo("/v1/journal/entries/saga-1");
		assertThat(handler.lastAuthHeader()).isEqualTo("Bearer test-key");

		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).seq()).isEqualTo(1);
		assertThat(entries.get(0).phase()).isEqualTo(Phase.INTENT);
		assertThat(entries.get(1).seq()).isEqualTo(2);
		assertThat(entries.get(1).phase()).isEqualTo(Phase.EXECUTED);
		assertThat(entries.get(1).payload()).isEqualTo("out");
	}

	@Test
	void entries_returnsEmptyListWhenNoEntries() {
		handler.respondWith(200, "{\"entries\":[]}");

		List<JournalEntry> entries = new CloudSideEffectJournal("key", baseUrl).entries("empty-saga");

		assertThat(entries).isEmpty();
	}

	@Test
	void entries_encodesSpecialCharactersInSagaId() {
		handler.respondWith(200, "{\"entries\":[]}");

		new CloudSideEffectJournal("key", baseUrl).entries("saga/with/slashes");

		assertThat(handler.lastPath()).isEqualTo("/v1/journal/entries/saga%2Fwith%2Fslashes");
	}

	@Test
	void entries_throwsCloudJournalExceptionOnNon2xxResponse() {
		handler.respondWith(404, "{\"error\":\"saga not found\"}");

		assertThatThrownBy(() -> new CloudSideEffectJournal("key", baseUrl).entries("missing"))
				.isInstanceOf(CloudJournalException.class)
				.hasMessageContaining("HTTP 404");
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
	void constructor_throwsOnBlankBaseUrl() {
		assertThatThrownBy(() -> new CloudSideEffectJournal("key", ""))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("base URL");
	}

	@Test
	void constructor_stripsTrailingSlashFromBaseUrl() {
		handler.respondWith(200, "{\"entries\":[]}");

		// Should NOT double-slash the path
		new CloudSideEffectJournal("key", baseUrl + "/").entries("saga-1");

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

	/**
	 * Minimal HTTP handler that captures request details and serves a canned response.
	 */
	private static class StubHandler implements HttpHandler {

		private volatile int statusCode = 200;
		private volatile String responseBody = "";

		private final AtomicReference<String> lastMethod = new AtomicReference<>();
		private final AtomicReference<String> lastPath = new AtomicReference<>();
		private final AtomicReference<String> lastAuth = new AtomicReference<>();
		private final AtomicReference<String> lastContentType = new AtomicReference<>();
		private final AtomicReference<String> lastBody = new AtomicReference<>();

		void respondWith(int status, String body) {
			this.statusCode = status;
			this.responseBody = body;
		}

		String lastMethod() { return lastMethod.get(); }
		String lastPath() { return lastPath.get(); }
		String lastAuthHeader() { return lastAuth.get(); }
		String lastContentTypeHeader() { return lastContentType.get(); }
		String lastBody() { return lastBody.get(); }

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			lastMethod.set(exchange.getRequestMethod());
			lastPath.set(exchange.getRequestURI().getRawPath());
			lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			lastBody.set(new String(exchange.getRequestBody().readAllBytes()));

			byte[] bytes = responseBody.getBytes();
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(statusCode, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
	}
}
