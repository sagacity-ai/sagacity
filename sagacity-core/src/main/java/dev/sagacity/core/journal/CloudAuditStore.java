package dev.sagacity.core.journal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * {@link AuditStore} that forwards entries to the Sagacity Cloud API.
 *
 * <p>Appends are retried with exponential backoff on transient failures
 * (network errors, 5xx, 429). An append that cannot be persisted after all
 * retries throws {@link CloudJournalException} — losing a journal entry is
 * worse than a failed tool call, so the saga is aborted rather than
 * silently continuing with an incomplete audit trail.
 *
 * <p>Select via auto-configuration by setting {@code sagacity.cloud.api-key}.
 */
public final class CloudAuditStore implements AuditStore {

    private static final String DEFAULT_BASE_URL = "https://sagacity-cloud-api.hardhustle1988.workers.dev";

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final CloudJournalSerializer serializer;
    private final CloudJournalRetryPolicy retryPolicy;

    public CloudAuditStore(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public CloudAuditStore(String apiKey, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Sagacity Cloud API key must not be blank");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.serializer = new CloudJournalSerializer();
        this.retryPolicy = CloudJournalRetryPolicy.DEFAULT;
    }

    @Override
    public AuditEntry append(String sagaId, String toolName, Phase phase, String input) {
        String body = serializer.serializeAppendRequest(sagaId, toolName, phase, input);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/journal/append"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            sleepBefore(attempt);
            try {
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    return serializer.deserializeEntry(response.body());
                }
                if (!retryPolicy.isRetryableStatus(response.statusCode())) {
                    throw new CloudJournalException(
                            "Cloud journal append failed with HTTP " + response.statusCode()
                            + ": " + response.body());
                }
                if (attempt == retryPolicy.maxAttempts()) {
                    throw new CloudJournalException(
                            "Cloud journal append failed after " + attempt
                            + " attempts, last HTTP status: " + response.statusCode());
                }
            } catch (IOException ex) {
                if (!retryPolicy.isRetryableException(ex) || attempt == retryPolicy.maxAttempts()) {
                    throw new CloudJournalException(
                            "Cloud journal append failed after " + attempt + " attempts", ex);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CloudJournalException("Cloud journal append interrupted", ex);
            }
        }
        throw new CloudJournalException("Cloud journal append exhausted retries for sagaId=" + sagaId);
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/journal/entries?sagaId=" + sagaId))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new CloudJournalException(
                        "Cloud journal read failed with HTTP " + response.statusCode());
            }
            return serializer.deserializeEntries(response.body());
        } catch (IOException ex) {
            throw new CloudJournalException("Cloud journal read failed for sagaId=" + sagaId, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CloudJournalException("Cloud journal read interrupted", ex);
        }
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId, Class<? extends Phase> phaseType) {
        // Cloud API does not yet support phase filtering — filter in memory.
        return findBySagaId(sagaId).stream()
                .filter(e -> phaseType.isInstance(e.phase()))
                .toList();
    }

    private void sleepBefore(int attempt) {
        var delay = retryPolicy.delayBefore(attempt);
        if (delay.isZero()) return;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CloudJournalException("Cloud journal sleep interrupted", ex);
        }
    }
}
