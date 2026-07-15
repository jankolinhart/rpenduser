package com.reelypops.rpenduser.device;

import com.reelypops.rpenduser.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test of the internal admin device surface (key-authed, no end-user JWT) against a Testcontainers
 * Postgres. Devices are seeded through the real end-user registration path so the admin views read live rows.
 */
@SpringBootTest(properties = "rp.internal.api-key=test-internal-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminDeviceControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";

    @Autowired
    MockMvc mockMvc;

    private void register(UUID user, String deviceId, String platform) throws Exception {
        mockMvc.perform(post("/enduser/v1/devices").with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"platform\":\"" + platform + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void countsReturnsThePerUserTally() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "adm-c1", "macOS 14.5");
        register(user, "adm-c2", "Windows 11");

        mockMvc.perform(get("/enduser/v1/internal/devices/counts").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId=='" + user + "')].count", contains(2)));
    }

    @Test
    void forUserReturnsEveryFieldForThatUserOnly() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "adm-u1", "macOS 14.5");
        register(UUID.randomUUID(), "adm-other", "Linux");

        mockMvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deviceId").value("adm-u1"))
                .andExpect(jsonPath("$[0].platform").value("macOS 14.5"))
                .andExpect(jsonPath("$[0].firstSeenAt").exists())
                .andExpect(jsonPath("$[0].lastSeenAt").exists());
    }

    @Test
    void missingKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/enduser/v1/internal/devices/counts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/enduser/v1/internal/users/{id}/devices", UUID.randomUUID())
                        .header(KEY_HEADER, "not-the-key"))
                .andExpect(status().isUnauthorized());
    }
}
