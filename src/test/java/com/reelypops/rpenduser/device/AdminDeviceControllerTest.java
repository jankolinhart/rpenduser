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
        register(user, deviceId, platform, null);
    }

    private void register(UUID user, String deviceId, String platform, String name) throws Exception {
        String named = name == null ? "" : ",\"deviceName\":\"" + name + "\"";
        mockMvc.perform(post("/enduser/v1/devices").with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"platform\":\"" + platform + "\"" + named + "}"))
                .andExpect(status().isOk());
    }

    private void heartbeat(UUID user, String deviceId, String appVersion) throws Exception {
        mockMvc.perform(post("/enduser/v1/internal/users/{id}/devices/heartbeat", user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"online\":true,\"stateHash\":\"h\","
                                + "\"appVersion\":\"" + appVersion + "\"}"))
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

    /**
     * "2 OF 3 LIVE" IS THE COLUMN THAT IS WORTH HAVING. A bare count says nothing about whether the user in
     * front of you can be helped this minute.
     *
     * <p>It has to be counted in the query: this runs for EVERY user on one dashboard load, which is also
     * why {@code device(last_seen_at)} is indexed.
     */
    @Test
    void countsSaysHowManyOfThoseDevicesAreLIVE() throws Exception {
        UUID user = UUID.randomUUID();
        register(user, "adm-live-1", "macOS 14.5");
        register(user, "adm-live-2", "Windows 11");

        // Both were just registered, so both are inside the live band.
        mockMvc.perform(get("/enduser/v1/internal/devices/counts").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId=='" + user + "')].live", contains(2)));
    }

    @Test
    void aMachineThatSaidGoodbyeStopsCountingAsLiveIMMEDIATELY() throws Exception {
        // The summary must agree with the drill-down. A device that announced it was closing reads OFFLINE
        // there at once, so counting it as live here would put two different answers on one screen.
        UUID user = UUID.randomUUID();
        register(user, "adm-bye-1", "macOS 14.5");
        register(user, "adm-bye-2", "Windows 11");

        mockMvc.perform(post("/enduser/v1/internal/users/{id}/devices/goodbye", user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"adm-bye-1\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/enduser/v1/internal/devices/counts").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId=='" + user + "')].count", contains(2)))
                .andExpect(jsonPath("$[?(@.userId=='" + user + "')].live", contains(1)));
    }

    @Test
    void aDeviceCarriesItsNamePresenceAndClientBuild() throws Exception {
        // The three questions support is actually asked: which machine, is it running, what is it on.
        UUID user = UUID.randomUUID();
        register(user, "adm-full", "macOS 14.5", "Kitchen iMac");
        heartbeat(user, "adm-full", "1.4.2");

        mockMvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceName").value("Kitchen iMac"))
                .andExpect(jsonPath("$[0].presence").value("LIVE"))
                .andExpect(jsonPath("$[0].appVersion").value("1.4.2"));
    }

    @Test
    void aGoodbyeIsShownAsOfflineWithTheStampThatExplainsIt() throws Exception {
        // OFFLINE arriving early is only trustworthy if the console can see WHY. Absence explains nothing,
        // because a crash says nothing — so the stamp is carried rather than folded into the band.
        UUID user = UUID.randomUUID();
        register(user, "adm-bye-view", "macOS 14.5", "Kitchen iMac");

        mockMvc.perform(post("/enduser/v1/internal/users/{id}/devices/goodbye", user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"adm-bye-view\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].presence").value("OFFLINE"))
                .andExpect(jsonPath("$[0].shutdownAt").exists());
    }

    @Test
    void aDeviceShowsTheInstagramAccountItIsWORKING() throws Exception {
        // The operator's ask: surface the in-focus handle beside the machine. Presence alone says a machine
        // is running; this says what it is running, which is what turns "nothing is happening" into an
        // answer instead of a hunt.
        UUID user = UUID.randomUUID();
        register(user, "adm-focus", "macOS 14.5", "Kitchen iMac");
        mockMvc.perform(post("/enduser/v1/internal/users/{id}/devices/report", user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"adm-focus\",\"stateHash\":\"h1\",\"igAccounts\":"
                                + "[{\"handle\":\"jean_marc\",\"inFocus\":true}]}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/enduser/v1/internal/users/{id}/devices", user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].focusedHandle").value("jean_marc"))
                .andExpect(jsonPath("$[0].focusedHandleAt").exists());
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
