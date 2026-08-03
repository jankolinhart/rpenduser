package com.reelypops.rpenduser.drift;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Verifies rpenduser's outbound drift client targets rpsupportgroup's internal drift endpoint, relays the shared
 * {@code X-Internal-Api-Key}, serialises the {@link DriftReportRequest} body, and surfaces a non-2xx to its caller.
 */
class SupportGroupDriftClientTest {

    private static final String BASE = "http://rpsupportgroup:8080";
    private static final String DRIFT = BASE + "/supportgroup/v1/internal/groups/grp.one/drift";
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MockRestServiceServer server;
    private SupportGroupDriftClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SupportGroupDriftClient(builder, BASE, "test-key");
    }

    @Test
    void reportDriftPostsAMarkerDisagreeBodyWithTheApiKey() {
        server.expect(requestTo(DRIFT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(SupportGroupDriftClient.API_KEY_HEADER, "test-key"))
                .andExpect(jsonPath("$.kind").value("MARKER_DISAGREE"))
                .andExpect(jsonPath("$.reporterDeviceId").value("dev-1"))
                .andExpect(jsonPath("$.reporterUserId").value(USER.toString()))
                .andExpect(jsonPath("$.agreePass").value(5))
                .andExpect(jsonPath("$.disagreePass").value(2))
                .andExpect(jsonPath("$.persistenceCount").value(4))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        client.reportDrift("grp.one", new DriftReportRequest("MARKER_DISAGREE", "dev-1", USER, null, 5, 2, 4));

        server.verify();
    }

    @Test
    void reportDriftPostsANewOwnerNomination() {
        server.expect(requestTo(DRIFT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(SupportGroupDriftClient.API_KEY_HEADER, "test-key"))
                .andExpect(jsonPath("$.kind").value("NEW_OWNER"))
                .andExpect(jsonPath("$.nominatedOwnerHandle").value("cand.owner"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        client.reportDrift("grp.one", new DriftReportRequest("NEW_OWNER", "dev-1", USER, "cand.owner", null, null, null));

        server.verify();
    }

    @Test
    void reportDriftSurfacesANon2xx() {
        server.expect(requestTo(DRIFT)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.reportDrift("grp.one",
                new DriftReportRequest("MARKER_DISAGREE", "dev-1", USER, null, 1, 1, 1)))
                .isInstanceOf(RestClientResponseException.class);

        server.verify();
    }
}
