package com.reelypops.rpenduser.drift;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * rpenduser's first east-west OUTBOUND client (M5 re-vet consumer): forwards one client-reported drift observation to
 * rpsupportgroup's internal drift-ingest endpoint, authenticated by the shared {@code X-Internal-Api-Key} (the same
 * key rpenduser accepts on its own internal surface). A non-2xx surfaces as the RestClient default
 * {@code RestClientResponseException}; the caller ({@link DriftForwardingService}) forwards best-effort.
 */
@Component
public class SupportGroupDriftClient {

    static final String API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String DRIFT_PATH = "/supportgroup/v1/internal/groups/{igAccount}/drift";

    private final RestClient restClient;

    public SupportGroupDriftClient(RestClient.Builder builder,
                                   @Value("${rp.supportgroup.base-url:}") String baseUrl,
                                   @Value("${rp.internal.api-key:}") String apiKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .build();
    }

    /** Forward one drift observation for {@code igAccount}. Throws on a non-2xx (the caller decides best-effort). */
    public void reportDrift(String igAccount, DriftReportRequest req) {
        restClient.post()
                .uri(DRIFT_PATH, igAccount)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .toBodilessEntity();
    }
}
