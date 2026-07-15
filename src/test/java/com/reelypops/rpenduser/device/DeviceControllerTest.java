package com.reelypops.rpenduser.device;

import com.reelypops.rpenduser.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack device-registry test: real controller + service + repository against a Testcontainers Postgres
 * (Liquibase-migrated). Auth is a mock JWT whose subject is the user id (rpauth's {@code sub}); each test uses
 * a fresh user id so the cases stay isolated without cleanup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeviceControllerTest {

    @Autowired
    MockMvc mockMvc;

    private static RequestPostProcessor asUser(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()));
    }

    private static String body(String deviceId, String platform) {
        return "{\"deviceId\":\"" + deviceId + "\",\"platform\":\"" + platform + "\"}";
    }

    @Test
    void registerCreatesDevice() throws Exception {
        mockMvc.perform(post("/enduser/v1/devices").with(asUser(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("dev-hash-1", "macOS 14.5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("dev-hash-1"))
                .andExpect(jsonPath("$.platform").value("macOS 14.5"))
                .andExpect(jsonPath("$.firstSeenAt").exists())
                .andExpect(jsonPath("$.lastSeenAt").exists());
    }

    @Test
    void registerIsAnIdempotentHeartbeat() throws Exception {
        UUID user = UUID.randomUUID();
        mockMvc.perform(post("/enduser/v1/devices").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body("dev-hash-2", "macOS 14.4")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/enduser/v1/devices").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body("dev-hash-2", "macOS 14.5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("macOS 14.5"));

        // still exactly one device for the user — an upsert, not a duplicate
        mockMvc.perform(get("/enduser/v1/devices").with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listReturnsOnlyTheUsersDevices() throws Exception {
        UUID user = UUID.randomUUID();
        mockMvc.perform(post("/enduser/v1/devices").with(asUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(body("d1", "macOS 14.5"))).andExpect(status().isOk());
        mockMvc.perform(post("/enduser/v1/devices").with(asUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(body("d2", "Windows 11"))).andExpect(status().isOk());
        // a different user's device must not leak in
        mockMvc.perform(post("/enduser/v1/devices").with(asUser(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON).content(body("other", "Linux"))).andExpect(status().isOk());

        mockMvc.perform(get("/enduser/v1/devices").with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void removeDeletesTheDevice() throws Exception {
        UUID user = UUID.randomUUID();
        mockMvc.perform(post("/enduser/v1/devices").with(asUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(body("gone", "Linux"))).andExpect(status().isOk());

        mockMvc.perform(delete("/enduser/v1/devices/{id}", "gone").with(asUser(user)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/enduser/v1/devices").with(asUser(user)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void removeUnknownDeviceReturns404() throws Exception {
        mockMvc.perform(delete("/enduser/v1/devices/{id}", "never").with(asUser(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(post("/enduser/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON).content(body("x", "macOS")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankDeviceIdIsRejected() throws Exception {
        mockMvc.perform(post("/enduser/v1/devices").with(asUser(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("", "macOS")))
                .andExpect(status().isBadRequest());
    }
}
