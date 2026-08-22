package com.reelypops.rpenduser.membership;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * rpenduser's outbound membership client (B6 follow-gating): forwards a user's support-group memberships — parsed from
 * the M5.1 report — to rpsupportgroup's internal memberships endpoint, the record home for {@code sg_membership}.
 * Modeled on {@link com.reelypops.rpenduser.drift.SupportGroupDriftClient}: same base URL and the same target-owned
 * {@code X-Internal-Api-Key} (each service owns a distinct internal key, so the caller presents the TARGET's key, not
 * its own). A non-2xx surfaces as the RestClient default {@code RestClientResponseException}; the caller
 * ({@link MembershipForwardingService}) forwards best-effort.
 */
@Component
public class SupportGroupMembershipClient {

    static final String API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String MEMBERSHIPS_PATH = "/supportgroup/v1/internal/users/{userId}/memberships";

    private final RestClient restClient;

    public SupportGroupMembershipClient(RestClient.Builder builder,
                                        @Value("${rp.supportgroup.base-url:}") String baseUrl,
                                        @Value("${rp.supportgroup.api-key:}") String apiKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .build();
    }

    /** Forward {@code userId}'s memberships as the per-row idempotent-upsert array. Throws on a non-2xx. */
    public void reportMemberships(UUID userId, List<MembershipReportEntry> memberships) {
        restClient.post()
                .uri(MEMBERSHIPS_PATH, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(memberships)
                .retrieve()
                .toBodilessEntity();
    }
}
