package com.reelypops.rpenduser.stop;

import com.reelypops.rpenduser.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

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
     * A MACHINE THAT HAS STOPPED IS NOT TOLD AGAIN. Repeating the order every minute would make a stopped
     * client re-run its stop for ever, and would leave the console unable to tell obeyed from ordered.
     */
    @Test
    void anAcknowledgedOrderIsNotRepeated() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "ack-1");
        order(user, "DISABLE");

        String body = beat(user, "ack-1", null).andReturn().getResponse().getContentAsString();
        String orderId = body.replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");

        beat(user, "ack-1", orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopDirective").doesNotExist());
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
        beat(user, "esc-1", first).andExpect(jsonPath("$.stopDirective").doesNotExist());

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

    @Test
    void theStopSurfaceIsKeyAuthed() throws Exception {
        mvc.perform(post("/enduser/v1/internal/users/{id}/stop-order", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"KILL\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/enduser/v1/internal/users/{id}/stop-order", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
