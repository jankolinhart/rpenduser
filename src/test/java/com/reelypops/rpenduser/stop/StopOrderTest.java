package com.reelypops.rpenduser.stop;

import com.reelypops.rpenduser.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AN ADMIN DECISION THAT CAN REACH A RUNNING MACHINE — and, just as importantly, be lifted again.
 *
 * <p>Automation is local by design (D16): it must survive the cloud being unreachable, so nothing the cloud
 * fails to say can ever stop it. That is why the instruction is a POSITIVE order carried on the 60-second
 * heartbeat, and why its ABSENCE — no row, an older backend, a failed query, an outage — is indistinguishable
 * from "carry on". The first two tests are that rule; everything else is detail.
 */
@SpringBootTest(properties = "rp.internal.api-key=test-internal-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StopOrderTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";

    @Autowired MockMvc mvc;
    @Autowired StopOrderService stopOrders;
    @Autowired com.reelypops.rpenduser.device.DeviceRepository devices;
    @Autowired UserStopOrderRepository orderRows;

    /** Backdate a machine's last check-in, so "closed for weeks" can be tested without waiting weeks. */
    private void lastSeen(UUID user, String deviceId, java.time.Duration ago) {
        var device = devices.findByUserIdAndDeviceId(user, deviceId).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(device, "lastSeenAt", Instant.now().minus(ago));
        devices.save(device);
    }

    private void register(UUID user, String deviceId) throws Exception {
        mvc.perform(post("/enduser/v1/devices").with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"platform\":\"macOS 14.5\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions beat(UUID user, String deviceId, String ack)
            throws Exception {
        String ackField = ack == null ? "" : ",\"ackStopOrderId\":\"" + ack + "\"";
        return mvc.perform(post("/enduser/v1/internal/users/{id}/devices/heartbeat", user).header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"online\":true,\"stateHash\":\"h\"" + ackField + "}"));
    }

    private void order(UUID user, String action) throws Exception {
        mvc.perform(post("/enduser/v1/internal/users/{id}/stop-order", user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"orderedBy\":\"root\"}"))
                .andExpect(status().isOk());
    }

    /**
     * RULE 1, AS A TEST. A user nobody has acted on gets NO instruction — and the absence is the same
     * absence an outage produces, which is exactly why it is safe.
     */
    @Test
    void aUserWithNoOrderIsToldNOTHING() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "quiet-1");

        beat(user, "quiet-1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopDirective").doesNotExist());
    }

    @Test
    void anOrderReachesTheMachineOnItsNextBeat() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "beat-1");
        order(user, "KILL");

        beat(user, "beat-1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopDirective.action").value("KILL"))
                .andExpect(jsonPath("$.stopDirective.orderId").value(notNullValue()));
    }

    /**
     * AN OUTSTANDING ORDER KEEPS SAYING SO, even to a machine that has acknowledged it. That is what makes
     * the instruction reversible.
     *
     * <p>This originally stopped repeating after an ack, on the reasoning that a stopped machine does not
     * need telling twice. Designing the client half showed why that is wrong: the client must tell "stop"
     * from "you may work" from "I could not ask", and an outage has to land on the third. If an outstanding
     * order fell silent, a stopped machine would be told exactly what an unreachable cloud tells it —
     * nothing — and could not tell a release from an outage. It would resume work either way.
     *
     * <p>The client applies an order once per orderId and ignores repeats, so nothing is re-run.
     */
    @Test
    void anOutstandingOrderIsSentAGAINevenAfterItIsAcknowledged() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "ack-1");
        order(user, "DISABLE");

        String body = beat(user, "ack-1", null).andReturn().getResponse().getContentAsString();
        String orderId = body.replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");

        beat(user, "ack-1", orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopDirective.action").value("DISABLE"))
                .andExpect(jsonPath("$.stopDirective.orderId").value(orderId));
    }

    /**
     * SILENCE MEANS ONE THING ONLY: no order. That is the property the repeat above exists to protect —
     * without it, "released" and "cloud unreachable" would look identical to a stopped machine.
     */
    @Test
    void silenceMeansNOorderRatherThanNOnews() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "silent-1");
        order(user, "KILL");
        beat(user, "silent-1", null).andExpect(jsonPath("$.stopDirective.action").value("KILL"));

        mvc.perform(delete("/enduser/v1/internal/users/{id}/stop-order", user).header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());

        beat(user, "silent-1", null).andExpect(jsonPath("$.stopDirective").doesNotExist());
    }

    /**
     * AN OPERATOR WHO PRESSES STOP AGAIN MEANS IT. Escalating from "finish up" to "stop now" must reach a
     * machine that already obeyed the gentler order, so a re-issue carries a new id.
     */
    @Test
    void escalatingFromDisableToKillReachesAMachineThatAlreadyObeyed() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "esc-1");
        order(user, "DISABLE");
        String first = beat(user, "esc-1", null).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
        order(user, "KILL");

        beat(user, "esc-1", null)
                .andExpect(jsonPath("$.stopDirective.action").value("KILL"));
    }

    /**
     * LIFTING MUST BE AT LEAST AS RELIABLE AS ISSUING. Three independent reviewers of this design each
     * found the same fault — every stop path defended, the un-stop path an afterthought — so clearing is a
     * first-class operation and is IDEMPOTENT: a caller re-activating an account must never have to handle
     * "there was nothing to clear" as a condition.
     */
    @Test
    void clearingLetsTheMachinesWorkAgain() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "clear-1");
        order(user, "KILL");
        beat(user, "clear-1", null).andExpect(jsonPath("$.stopDirective.action").value("KILL"));

        mvc.perform(delete("/enduser/v1/internal/users/{id}/stop-order", user).header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());

        beat(user, "clear-1", null).andExpect(jsonPath("$.stopDirective").doesNotExist());
    }

    @Test
    void clearingSomethingThatWasNeverOrderedIsSTILLnoContent() throws Exception {
        mvc.perform(delete("/enduser/v1/internal/users/{id}/stop-order", UUID.randomUUID()).header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());
    }

    /**
     * THE ORDER IS ABOUT AN ACCOUNT, NOT A MACHINE. A laptop that registers after the decision was taken is
     * covered by it — which is what an operator means by "stop this customer".
     */
    @Test
    void aMachineThatAPPEARSafterTheOrderIsCoveredByIt() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "before-1");
        order(user, "KILL");

        register(user, "after-1");

        beat(user, "after-1", null)
                .andExpect(jsonPath("$.stopDirective.action").value("KILL"));
    }

    /** A stale or malformed acknowledgement is ignored, never an error — it is old news, not a fault. */
    @Test
    void aStaleOrMalformedAcknowledgementIsIgnored() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "stale-1");
        order(user, "KILL");

        beat(user, "stale-1", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopDirective.action").value("KILL"));
        beat(user, "stale-1", "not-a-uuid")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopDirective.action").value("KILL"));
    }

    /**
     * NOTE: this passes on a KILL, and would now fail on a SIGN_OUT — deliberately. A sign-out's
     * acknowledgement is not reported once obeyed, because it describes a moment rather than a condition.
     */
    @Test
    void theAdminViewShowsWhichMachinesHaveOBEYED() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "obey-1");
        order(user, "KILL");
        String orderId = beat(user, "obey-1", null).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
        beat(user, "obey-1", orderId);

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stopAckedOrderId").value(orderId))
                .andExpect(jsonPath("$[0].stopAckedAt").exists());
    }

    /**
     * THE QUESTION AN OPERATOR ACTUALLY HAS AFTER PRESSING STOP: did it land?
     *
     * <p>"Ordered" is the half they already know. What they cannot see without this is that one machine has
     * obeyed and another — closed at the time — has not.
     */
    @Test
    void theAdminViewSeparatesSTOPPEDfromSTOPPENDING() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "landed-1");
        register(user, "closed-1");
        order(user, "KILL");

        String orderId = beat(user, "landed-1", null).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
        beat(user, "landed-1", orderId);          // this one obeyed; "closed-1" never checked in

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.deviceId=='landed-1')].stopPending").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.deviceId=='closed-1')].stopPending").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.deviceId=='closed-1')].stopAction").value(org.hamcrest.Matchers.contains("KILL")));
    }

    /** No order means nothing pending — the ordinary case, and it must not read as a stop. */
    @Test
    void aUserWithNoOrderHasNothingPENDING() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "calm-1");

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stopPending").value(false))
                .andExpect(jsonPath("$[0].stopAction").doesNotExist());
    }

    /** Clearing the order clears the pending state too — a lifted stop must not still read as outstanding. */
    @Test
    void clearingAnOrderStopsItReadingAsPending() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "lift-1");
        order(user, "KILL");
        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopPending").value(true));

        mvc.perform(delete("/enduser/v1/internal/users/{id}/stop-order", user).header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopPending").value(false));
    }

    @Test
    void theStopSurfaceIsKeyAuthed() throws Exception {
        mvc.perform(post("/enduser/v1/internal/users/{id}/stop-order", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"KILL\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/enduser/v1/internal/users/{id}/stop-order", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------------
    // SIGN_OUT — the one order that stops nothing, and the one that must not outlive the press
    // ------------------------------------------------------------------------------------------------

    /**
     * Reset rides the same carrier as the other three.
     *
     * <p>It exists because the alternative was Reset alone waiting up to eight minutes for a keep-alive to
     * be refused while Disable, Kill and Remove all landed in sixty seconds. Three of four actions being
     * prompt and one being slow reads as a fault whether or not it is one.
     */
    @Test
    void aSignOutIsCarriedToTheMachineLikeAnyOtherOrder() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "sign-out-1");
        order(user, "SIGN_OUT");

        beat(user, "sign-out-1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopDirective.action").value("SIGN_OUT"))
                .andExpect(jsonPath("$.stopDirective.orderId", notNullValue()));
    }

    /**
     * <strong>AND IT EXPIRES.</strong> The fault this prevents: a Reset leaves the account ACTIVE, so a
     * latched sign-out would sit in the table indefinitely and a laptop opened for the first time a
     * fortnight later would sign itself out sixty seconds after connecting — no administrator anywhere near
     * it, and no button to press to make it stop.
     *
     * <p>Nothing is lost by the expiry: that machine's refresh token was revoked when the button was
     * pressed, so it signs out on its next keep-alive regardless. The order only makes it PROMPT for the
     * machines that were actually running.
     */
    @Test
    void aSignOutStopsBeingServedOnceItsWindowHasPassed() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "sign-out-2");
        order(user, "SIGN_OUT");
        Instant issued = Instant.now();

        assertThat(stopOrders.standingOrder(user, issued.plus(Duration.ofMinutes(9))))
                .as("still inside the window, and every running machine must still hear it")
                .isPresent();
        assertThat(stopOrders.standingOrder(user, issued.plus(Duration.ofMinutes(11))))
                .as("past the window — it must never reach a machine that connects later")
                .isEmpty();
    }

    /** A latching order is the opposite, and stands until somebody lifts it. */
    @Test
    void aDisableStandsHoweverLongItHasBeenThere() {
        UUID user = UUID.randomUUID();
        stopOrders.order(user, StopAction.DISABLE, "someone");

        assertThat(stopOrders.standingOrder(user, Instant.now().plus(Duration.ofDays(30))))
                .as("Enable is what ends a disable, never the passage of time")
                .isPresent();
    }

    /** Kill latches for the same reason, and is worth pinning separately — it is the harshest one. */
    @Test
    void aKillStandsHoweverLongItHasBeenThere() {
        UUID user = UUID.randomUUID();
        stopOrders.order(user, StopAction.KILL, "someone");

        assertThat(stopOrders.standingOrder(user, Instant.now().plus(Duration.ofDays(30))))
                .isPresent();
    }

    /**
     * <strong>A SIGN-OUT MUST NOT LIFT A STOP.</strong> Reset is offered on every account including a
     * disabled one, so without this guard the gentlest button in the console would be the one that undid
     * the harshest: the DISABLE would be overwritten by an order that stops nothing, and every machine
     * belonging to that customer would go back to work on its next beat.
     *
     * <p>Only Enable lifts a stop. That is rule 4's single door, and this is the test that keeps it single.
     */
    @Test
    void aSignOutCannotDisplaceAStandingDisable() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "no-downgrade-1");
        order(user, "DISABLE");

        order(user, "SIGN_OUT");

        beat(user, "no-downgrade-1", null)
                .andExpect(jsonPath("$.stopDirective.action").value("DISABLE"));
    }

    /** The same, for the one it would matter most on. */
    @Test
    void aSignOutCannotDisplaceAStandingKill() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "no-downgrade-2");
        order(user, "KILL");

        order(user, "SIGN_OUT");

        beat(user, "no-downgrade-2", null)
                .andExpect(jsonPath("$.stopDirective.action").value("KILL"));
    }

    /** But escalation still works in the direction that tightens: a sign-out gives way to a disable. */
    @Test
    void aDisableStillOverridesAStandingSignOut() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "escalate-1");
        order(user, "SIGN_OUT");

        order(user, "DISABLE");

        beat(user, "escalate-1", null)
                .andExpect(jsonPath("$.stopDirective.action").value("DISABLE"));
    }

    /** And a second Reset re-issues normally when nothing harsher is standing. */
    @Test
    void aSecondSignOutReissuesWhenNothingHarsherStands() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "reissue-1");
        order(user, "SIGN_OUT");
        String first = stopOrders.directiveFor(user).orElseThrow().orderId();

        order(user, "SIGN_OUT");

        assertThat(stopOrders.directiveFor(user).orElseThrow().orderId())
                .as("a machine that already obeyed the first must obey the second")
                .isNotEqualTo(first);
    }

    // ------------------------------------------------------------------------------------------------
    // AN ACKNOWLEDGEMENT IS ONLY MEANINGFUL BESIDE THE ORDER IT ANSWERS
    // ------------------------------------------------------------------------------------------------

    /**
     * <strong>A LIFTED STOP MUST STOP BEING REPORTED.</strong>
     *
     * <p>The ack columns outlive the order — Enable deletes the order row and touches no device — so a
     * machine that obeyed a disable an hour ago, was re-enabled, and has been working ever since still
     * carried its acknowledgement on the wire. The console rendered it in red, permanently. The operator met
     * exactly that: a LIVE machine, seen just now, working a handle, under a badge reading "stopped 39m
     * ago". It was not stopped, and nothing on the row could have told anyone otherwise.
     */
    @Test
    void anAcknowledgementDisappearsWithTheOrderItAnswered() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "lifted-1");
        order(user, "DISABLE");
        String orderId = beat(user, "lifted-1", null).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
        beat(user, "lifted-1", orderId);

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopAckedAt").exists());

        mvc.perform(delete("/enduser/v1/internal/users/{id}/stop-order", user).header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopAckedAt").doesNotExist())
                .andExpect(jsonPath("$[0].stopAckedOrderId").doesNotExist())
                .andExpect(jsonPath("$[0].stopAction").doesNotExist())
                .andExpect(jsonPath("$[0].stopPending").value(false));
    }

    /**
     * <strong>A SIGN-OUT IS A MOMENT, NOT A STATE.</strong>
     *
     * <p>Once a machine has obeyed one there is nothing left to report: the account is active, no work was
     * touched, and the only consequence was a login prompt the user has very likely already answered. It
     * produced a row reading "signed out 1m ago" beside a LIVE badge — and LIVE means the machine is sending
     * heartbeats, which needs an access token, which means it is signed IN. One row contradicting itself.
     */
    @Test
    void anObeyedSignOutIsNotReportedAfterwards() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "moment-1");
        order(user, "SIGN_OUT");
        String orderId = beat(user, "moment-1", null).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
        beat(user, "moment-1", orderId);

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopAckedAt").doesNotExist())
                .andExpect(jsonPath("$[0].stopPending").value(false));
    }

    /** A disable that has landed is the opposite: it describes a condition still in force, so it stays. */
    @Test
    void anObeyedDisableIsStillReported() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "standing-1");
        order(user, "DISABLE");
        String orderId = beat(user, "standing-1", null).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
        beat(user, "standing-1", orderId);

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopAckedAt").exists())
                .andExpect(jsonPath("$[0].stopAction").value("DISABLE"));
    }

    /**
     * <strong>AND A SIGN-OUT CANNOT BE PENDING ON A MACHINE THAT WILL NOT SEE IT.</strong>
     *
     * <p>The console tells an unreached machine that it "stops if it returns", which is true of a disable —
     * that order is still waiting. A sign-out is not: it expires within minutes. Two machines last seen 43
     * days ago were being promised a sign-out that could never reach them.
     */
    @Test
    void aSignOutIsNotPendingOnAMachineClosedLongerThanItWillLive() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "long-gone-1");
        lastSeen(user, "long-gone-1", java.time.Duration.ofDays(43));
        order(user, "SIGN_OUT");

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopPending").value(false));
    }

    /** A DISABLE on the same long-gone machine IS still pending — it stands until somebody lifts it. */
    @Test
    void aDisableIsStillPendingOnAMachineClosedForWeeks() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "long-gone-2");
        lastSeen(user, "long-gone-2", java.time.Duration.ofDays(43));
        order(user, "DISABLE");

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopPending").value(true));
    }

    /** But a machine seen moments ago hears a sign-out perfectly well, and must still be told it is coming. */
    @Test
    void aSignOutIsPendingOnAMachineSeenMomentsAgo() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "recent-1");
        order(user, "SIGN_OUT");

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopPending").value(true))
                .andExpect(jsonPath("$[0].stopAction").value("SIGN_OUT"));
    }

    /**
     * <strong>AN OLD ACKNOWLEDGEMENT MUST NOT ANSWER A NEW ORDER.</strong>
     *
     * <p>The nastiest shape of the same fault, and the only one where the id comparison is load-bearing on
     * its own: an operator escalates from Disable to Kill. The machine obeyed the disable, so it carries an
     * acknowledgement — for the wrong order — and the wire would assert that it had obeyed the kill.
     *
     * <p><strong>Do not try to confirm this through the console.</strong> An escalation sets
     * {@code stopPending}, so the surface renders "stopping…" and never reaches the branch that shows an
     * acknowledgement; checking it there would suggest the guard is dead code. Its value is that the
     * REGISTRY stops claiming something untrue, which is what protects the next consumer to read it.
     *
     * <p>Every other case is caught twice over by the momentary rule beside it; this one is caught here or
     * nowhere, which is why it is worth its own test. A mutation removing the comparison survives the whole
     * rest of this file.
     */
    @Test
    void anAcknowledgementOfAnEARLIERorderDoesNotAnswerTheOneNowStanding() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "escalated-1");
        order(user, "DISABLE");
        String first = beat(user, "escalated-1", null).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
        beat(user, "escalated-1", first);

        order(user, "KILL");   // a NEW id; the machine has not heard this one

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopAction").value("KILL"))
                .andExpect(jsonPath("$[0].stopPending").value(true))
                .andExpect(jsonPath("$[0].stopAckedAt").doesNotExist())
                .andExpect(jsonPath("$[0].stopAckedOrderId").doesNotExist());
    }

    /**
     * RE-ACKNOWLEDGING THE SAME ORDER MUST NOT MOVE THE CLOCK.
     *
     * <p>The client re-states what it is obeying on every ordinary beat, so a console that missed the first
     * acknowledgement catches up. Stamping "now" each time would make "stopped 40m ago" read "just now" for
     * ever — an age that resets itself is worse than no age at all, because it looks like the stop keeps
     * re-landing on a machine that in fact obeyed once, long ago.
     */
    @Test
    void reAcknowledgingTheSameOrderLeavesTheAgeAlone() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "steady-1");
        order(user, "DISABLE");
        String orderId = beat(user, "steady-1", null).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
        beat(user, "steady-1", orderId);

        String first = mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"stopAckedAt\":\"([^\"]+)\".*", "$1");

        beat(user, "steady-1", orderId);   // the ordinary beat, re-stating what it is obeying

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].stopAckedAt").value(first));
    }

    /**
     * AN EXPIRED ORDER CANNOT BE ACKNOWLEDGED. It is not in force, so a machine reporting that it obeyed it
     * is telling us about a decision we have since let lapse — stale news, and the same treatment a stale
     * order id already gets.
     */
    @Test
    void anExpiredOrderCannotBeAcknowledged() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "expired-1");
        order(user, "SIGN_OUT");
        String orderId = beat(user, "expired-1", null).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");

        // Age the order past its window rather than waiting ten minutes for it.
        var stored = orderRows.findById(user).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(stored, "orderedAt",
                Instant.now().minus(java.time.Duration.ofMinutes(11)));
        orderRows.save(stored);

        beat(user, "expired-1", orderId);

        assertThat(devices.findByUserIdAndDeviceId(user, "expired-1").orElseThrow().getStopAckedOrderId())
                .as("nothing was in force to obey")
                .isNull();
    }

    /**
     * A MACHINE THAT HAS JUST REGISTERED IS NOT SHUT DOWN.
     *
     * <p>Registration is what a client does the moment it relaunches, and it was the one call site that did
     * not clear the goodbye. Between launch and the first sixty-second beat the console showed a single row
     * saying three contradictory things at once: OFFLINE, "seen just now", and "closed cleanly a minute ago".
     */
    @Test
    void registeringAgainClearsAnEarlierGoodbye() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "relaunch-1");

        mvc.perform(post("/enduser/v1/internal/users/{id}/devices/goodbye", user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"relaunch-1\"}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].shutdownAt").exists());

        register(user, "relaunch-1");   // the app relaunches

        mvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].shutdownAt").doesNotExist());
    }
}
