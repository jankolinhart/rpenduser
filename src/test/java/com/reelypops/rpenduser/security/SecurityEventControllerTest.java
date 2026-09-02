package com.reelypops.rpenduser.security;

import com.reelypops.rpenduser.TestcontainersConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * rpenduser's stop history, READ AS SECURITY EVENTS.
 *
 * <p>Same shape rpauth speaks, so the console mirrors one vocabulary rather than growing a translation
 * layer per service — which is a thing to forget to update when the third service arrives.
 */
@SpringBootTest(properties = "rp.internal.api-key=test-internal-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityEventControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";

    @Autowired MockMvc mvc;

    private UUID userWithA(String action) throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(post("/enduser/v1/devices").with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"sec-" + UUID.randomUUID() + "\",\"platform\":\"macOS\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/enduser/v1/internal/users/{id}/stop-order", user).header(KEY_HEADER, KEY)
                        .header("X-Forwarded-For", "8.8.8.8, 203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"orderedBy\":\"root\"}"))
                .andExpect(status().isOk());
        return user;
    }

    /**
     * <strong>THE TYPE CARRIES THE ACTION.</strong> "A stop was issued" and "a KILL was issued" are
     * different sentences to an operator, and the alerting reads the type — so collapsing them would make
     * it impossible to treat a fleet-halting kill differently from a routine disable.
     */
    @Test
    void aKillIsReportedAsAKillAndIsRed() throws Exception {
        UUID user = userWithA("KILL");

        mvc.perform(get("/enduser/v1/internal/security-events").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + user + "')].type")
                        .value(org.hamcrest.Matchers.hasItem("STOP_ORDER_ISSUED_KILL")))
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + user + "')].severity")
                        .value(org.hamcrest.Matchers.hasItem("RED")));
    }

    /**
     * A DISABLE IS AMBER, not red. It is routine enough — billing, a cancellation, somebody leaving — that
     * a red alert on it would fire during ordinary admin work, and a red channel that fires during ordinary
     * work stops being read.
     */
    @Test
    void aDisableIsAmberBecauseItIsRoutine() throws Exception {
        UUID user = userWithA("DISABLE");

        mvc.perform(get("/enduser/v1/internal/security-events").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + user + "')].severity")
                        .value(org.hamcrest.Matchers.hasItem("AMBER")));
    }

    /** A sign-out stops nothing, so it is the record and never an alarm. */
    @Test
    void aSignOutIsInfoBecauseItStopsNothing() throws Exception {
        UUID user = userWithA("SIGN_OUT");

        mvc.perform(get("/enduser/v1/internal/security-events").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + user + "')].severity")
                        .value(org.hamcrest.Matchers.hasItem("INFO")));
    }

    /** CLEARING is a recovery, and a recovery is never an alarm — but it is always the record. */
    @Test
    void clearingIsRecordedAsInfoRatherThanAsAnAlarm() throws Exception {
        UUID user = userWithA("KILL");

        mvc.perform(delete("/enduser/v1/internal/users/{id}/stop-order", user).header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());

        mvc.perform(get("/enduser/v1/internal/security-events").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + user + "')].type")
                        .value(org.hamcrest.Matchers.hasItem("STOP_ORDER_CLEARED_KILL")))
                .andExpect(jsonPath("$[?(@.type == 'STOP_ORDER_CLEARED_KILL')].severity")
                        .value(org.hamcrest.Matchers.hasItem("INFO")));
    }

    /**
     * <strong>NOTHING HERE CLAIMS AN AUTHENTICATED ACTOR.</strong> This surface is a shared key with no
     * per-caller identity, so the label is a string somebody typed — and the console must be able to render
     * it differently from one that was proved.
     */
    @Test
    void everyEventReportsItsActorAsCLAIMED() throws Exception {
        UUID user = userWithA("KILL");

        mvc.perform(get("/enduser/v1/internal/security-events").header(KEY_HEADER, KEY).param("limit", "500"))
                .andExpect(jsonPath("$[?(@.actorAuthenticated == true)]").isEmpty())
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + user + "')].actor")
                        .value(org.hamcrest.Matchers.hasItem("root")))
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + user + "')].service")
                        .value(org.hamcrest.Matchers.hasItem("rpenduser")));
    }

    /** The address is the one the load balancer observed, never the one the caller offered. */
    @Test
    void theAddressIsTheOneTheLoadBalancerObserved() throws Exception {
        UUID user = userWithA("KILL");

        mvc.perform(get("/enduser/v1/internal/security-events").header(KEY_HEADER, KEY).param("limit", "500"))
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + user + "')].sourceIp")
                        .value(org.hamcrest.Matchers.hasItem("203.0.113.7")));
    }

    /**
     * <strong>THE FEED IS DRAINED, NOT SAMPLED.</strong> A page smaller than the burst must return the
     * OLDEST unseen events, so a reader that keeps a watermark and asks again eventually sees every one.
     *
     * <p>Newest-first would return the tail of the burst and let the reader's watermark step over the
     * middle of it — and a page only overflows when a lot is happening at once, which is precisely when
     * the record is worth having.
     */
    @Test
    void aBurstBiggerThanOnePageIsDrainedOldestFirstAndNothingIsSkipped() throws Exception {
        Instant watermark = Instant.now();
        List<UUID> ordered = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ordered.add(userWithA("KILL"));
        }

        List<UUID> drained = new ArrayList<>();
        String since = watermark.toString();
        for (int page = 0; page < 5 && drained.size() < ordered.size(); page++) {
            String body = mvc.perform(get("/enduser/v1/internal/security-events")
                            .header(KEY_HEADER, KEY).param("since", since).param("limit", "2"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> events = new ObjectMapper().readValue(body, new TypeReference<>() {});
            if (events.isEmpty()) {
                break;
            }
            events.forEach(e -> drained.add(UUID.fromString((String) e.get("subjectUserId"))));
            since = (String) events.get(events.size() - 1).get("occurredAt");
        }

        org.assertj.core.api.Assertions.assertThat(drained)
                .as("every event in the burst, in the order it happened")
                .containsExactlyElementsOf(ordered);
    }

    @Test
    void theSurfaceIsKeyAuthed() throws Exception {
        mvc.perform(get("/enduser/v1/internal/security-events"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * THE COUNT EXISTS BECAUSE A DRAIN CANNOT DETECT ITS OWN GAPS.
     *
     * <p>The mirror pages forward from a watermark derived from what it has already stored, so a row
     * landing below that watermark — a clock stepping backwards, a restore, two instances disagreeing — is
     * stepped over for ever while every read reports success. Comparing this count with the mirror's own
     * over a settled window is the only cheap question whose answer differs when rows have been lost.
     */
    @Test
    void countsWhatThisServiceHoldsInAWindow() throws Exception {
        userWithA("KILL");
        userWithA("DISABLE");

        mvc.perform(get("/enduser/v1/internal/security-events/count").header(KEY_HEADER, KEY)
                        .param("since", Instant.now().minus(Duration.ofHours(1)).toString())
                        .param("until", Instant.now().plus(Duration.ofMinutes(1)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void countsNothingOutsideTheWindow() throws Exception {
        userWithA("KILL");

        mvc.perform(get("/enduser/v1/internal/security-events/count").header(KEY_HEADER, KEY)
                        .param("since", Instant.now().minus(Duration.ofDays(9)).toString())
                        .param("until", Instant.now().minus(Duration.ofDays(8)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void theCountIsBehindTheSameKeyAsTheFeed() throws Exception {
        mvc.perform(get("/enduser/v1/internal/security-events/count")
                        .param("since", Instant.now().minus(Duration.ofHours(1)).toString())
                        .param("until", Instant.now().toString()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * REPAIR HAS TO BE ABLE TO GO BACK, AND HAS TO TERMINATE. The drain only moves forward from a
     * watermark; a reader that has found a hole needs a bounded re-read of a span it already passed.
     */
    @Test
    void readsAClosedWindowForARepair() throws Exception {
        UUID before = userWithA("KILL");
        Thread.sleep(10);
        Instant boundary = Instant.now();
        Thread.sleep(10);
        UUID after = userWithA("DISABLE");

        // SCOPED TO THIS TEST'S OWN SUBJECTS. The feed is drained from a shared database, so an unscoped
        // "no DISABLE in this window" asserts something about every other test in the class — which is how
        // the rpauth equivalent of this assertion failed in CI on 02/09/2026.
        mvc.perform(get("/enduser/v1/internal/security-events").header(KEY_HEADER, KEY)
                        .param("since", Instant.now().minus(Duration.ofHours(1)).toString())
                        .param("until", boundary.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + before + "')]").isNotEmpty())
                // …and the read stops at the bound rather than running on to the present.
                .andExpect(jsonPath("$[?(@.subjectUserId == '" + after + "')]").isEmpty());
    }
}
