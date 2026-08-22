package com.reelypops.rpenduser.membership;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Verifies rpenduser's outbound membership client targets rpsupportgroup's internal memberships endpoint (userId in
 * the path), relays the target-owned {@code X-Internal-Api-Key}, serialises the JSON-array upsert body, and surfaces
 * a non-2xx to its best-effort caller.
 */
class SupportGroupMembershipClientTest {

    private static final String BASE = "http://rpsupportgroup:8080";
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String MEMBERSHIPS = BASE + "/supportgroup/v1/internal/users/" + USER + "/memberships";

    private MockRestServiceServer server;
    private SupportGroupMembershipClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SupportGroupMembershipClient(builder, BASE, "test-key");
    }

    @Test
    void reportMembershipsPostsTheUpsertArrayWithTheApiKey() {
        server.expect(requestTo(MEMBERSHIPS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(SupportGroupMembershipClient.API_KEY_HEADER, "test-key"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].igHandle").value("my.handle"))
                .andExpect(jsonPath("$[0].igAccount").value("grp.one"))
                .andExpect(jsonPath("$[0].followingStatus").value("following"))
                .andExpect(jsonPath("$[1].igHandle").value("other.handle"))
                .andExpect(jsonPath("$[1].igAccount").value("grp.two"))
                .andExpect(jsonPath("$[1].followingStatus").value("not_following"))
                .andRespond(withStatus(HttpStatus.OK));

        client.reportMemberships(USER, List.of(
                new MembershipReportEntry("my.handle", "grp.one", "following"),
                new MembershipReportEntry("other.handle", "grp.two", "not_following")));

        server.verify();
    }

    @Test
    void reportMembershipsSurfacesANon2xx() {
        server.expect(requestTo(MEMBERSHIPS)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.reportMemberships(USER,
                List.of(new MembershipReportEntry("my.handle", "grp.one", "following"))))
                .isInstanceOf(RestClientResponseException.class);

        server.verify();
    }
}
