package com.reelypops.rpenduser.device;

import com.reelypops.rpenduser.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test of the internal device-write endpoint (key-authed, no end-user JWT) against a Testcontainers
 * Postgres — the surface the rpserver BFF calls to register a device on the signed-in user's behalf.
 */
@SpringBootTest(properties = "rp.internal.api-key=test-internal-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalDeviceControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";

    @Autowired
    MockMvc mockMvc;

    private ResultActions register(UUID user, String deviceId, String platform) throws Exception {
        return mockMvc.perform(post("/enduser/v1/internal/users/{userId}/devices", user).header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"platform\":\"" + platform + "\"}"));
    }

    private ResultActions heartbeat(UUID user, String deviceId, boolean online, String stateHash) throws Exception {
        return mockMvc.perform(post("/enduser/v1/internal/users/{userId}/devices/heartbeat", user).header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"online\":" + online + ",\"stateHash\":\"" + stateHash + "\"}"));
    }

    private ResultActions report(UUID user, String body) throws Exception {
        return mockMvc.perform(post("/enduser/v1/internal/users/{userId}/devices/report", user).header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void registersADeviceForTheUserAndReturnsItsFields() throws Exception {
        register(UUID.randomUUID(), "bff-d1", "macOS 14.5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("bff-d1"))
                .andExpect(jsonPath("$.platform").value("macOS 14.5"))
                .andExpect(jsonPath("$.firstSeenAt").exists())
                .andExpect(jsonPath("$.lastSeenAt").exists());
    }

    @Test
    void registrationIsAnIdempotentHeartbeat() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "bff-hb", "macOS 14.5").andExpect(status().isOk());
        register(user, "bff-hb", "macOS 14.6").andExpect(status().isOk());

        // Still exactly one device for the user — the second call was a heartbeat, not a duplicate.
        mockMvc.perform(get("/enduser/v1/internal/users/{userId}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].platform").value("macOS 14.6"));
    }

    @Test
    void missingKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/enduser/v1/internal/users/{userId}/devices", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/enduser/v1/internal/users/{userId}/devices", UUID.randomUUID())
                        .header(KEY_HEADER, "not-the-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── M5.1 backward contract ────────────────────────────────────────────────

    @Test
    void heartbeatCreatesTheDeviceAndAsksForAReportTheFirstTime() throws Exception {
        heartbeat(UUID.randomUUID(), "hb-new", true, "hash-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportNeeded").value(true)); // no stored report yet → send one
    }

    @Test
    void reportStoresTheSnapshotSoASubsequentMatchingHeartbeatIsQuiet() throws Exception {
        UUID user = UUID.randomUUID();
        // A report for a device the registry has not seen yet still lands (idempotent upsert), stored verbatim.
        report(user, "{\"schemaVersion\":1,\"deviceId\":\"hb-rpt\",\"stateHash\":\"hash-1\","
                + "\"session\":{\"online\":true},\"supportGroups\":[{\"sgId\":\"s1\",\"autoLiking\":true}]}")
                .andExpect(status().isAccepted());

        // Same hash as the stored report → no fresh report needed.
        heartbeat(user, "hb-rpt", true, "hash-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportNeeded").value(false));

        // The client's state moved on (new hash) → the backend asks for a fresh report.
        heartbeat(user, "hb-rpt", false, "hash-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportNeeded").value(true));
    }

    @Test
    void reportWithoutADeviceIdIsBadRequest() throws Exception {
        report(UUID.randomUUID(), "{\"schemaVersion\":1,\"stateHash\":\"hash-1\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportWithoutAStateHashIsBadRequest() throws Exception {
        report(UUID.randomUUID(), "{\"schemaVersion\":1,\"deviceId\":\"hb-x\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void heartbeatWithABlankDeviceIdIsBadRequest() throws Exception {
        mockMvc.perform(post("/enduser/v1/internal/users/{userId}/devices/heartbeat", UUID.randomUUID())
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"\",\"online\":true,\"stateHash\":\"h\"}"))
                .andExpect(status().isBadRequest());
    }
}
