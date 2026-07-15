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
}
