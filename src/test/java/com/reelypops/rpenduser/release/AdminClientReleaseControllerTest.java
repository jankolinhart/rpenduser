package com.reelypops.rpenduser.release;

import com.reelypops.rpenduser.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test of the internal client-release admin surface (M5.3c) against a Testcontainers Postgres — the
 * pending-release status, the "Publish Announcement Now" gate action, the DEV/TEST gate toggle, and the announcement
 * riding the heartbeat reply. Runs as the DEV stage so the gate can be toggled off (the PROD-forced-on rule is
 * unit-tested). {@code @Transactional} isolates each method from the singleton state row.
 */
@SpringBootTest(properties = {"rp.internal.api-key=test-internal-key", "rp.stage=dev"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminClientReleaseControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";
    private static final String BASE = "/enduser/v1/internal/client-release";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClientReleaseService releases;

    @Test
    void statusIsEmptyBeforeAnythingIsPublished() throws Exception {
        mockMvc.perform(get(BASE).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").doesNotExist())
                .andExpect(jsonPath("$.pendingAnnouncement").value(false))
                .andExpect(jsonPath("$.gateEnabled").value(true));
    }

    @Test
    void announceIs409WhenNothingIsPublished() throws Exception {
        mockMvc.perform(post(BASE + "/announce").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"highlights\":[\"x\"],\"urgency\":\"URGENT\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void pushingAPublishedVersionMakesItPendingUnderTheGate() throws Exception {
        mockMvc.perform(post(BASE + "/published").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"0.6.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value("0.6.0"))
                .andExpect(jsonPath("$.pendingAnnouncement").value(true));   // gate on (DEV default) → awaiting announce
    }

    @Test
    void aPublishedVersionShowsAsPendingThenAnnounces() throws Exception {
        releases.updatePublishedVersion("0.4.0");   // gate on (DEV default) → pending, not yet announced

        mockMvc.perform(get(BASE).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.publishedVersion").value("0.4.0"))
                .andExpect(jsonPath("$.announcedVersion").doesNotExist())
                .andExpect(jsonPath("$.pendingAnnouncement").value(true));

        mockMvc.perform(post(BASE + "/announce").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"highlights\":[\"Faster scans\",\"Bug fixes\"],\"urgency\":\"RECOMMENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcedVersion").value("0.4.0"))
                .andExpect(jsonPath("$.pendingAnnouncement").value(false))
                .andExpect(jsonPath("$.urgency").value("RECOMMENDED"))
                .andExpect(jsonPath("$.highlights[0]").value("Faster scans"))
                .andExpect(jsonPath("$.highlights[1]").value("Bug fixes"));
    }

    @Test
    void gateCanBeSwitchedOffOnDevSoAPublishAutoAnnounces() throws Exception {
        mockMvc.perform(put(BASE + "/gate").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateEnabled").value(false));

        releases.updatePublishedVersion("0.5.0");   // gate off → auto-announced

        mockMvc.perform(get(BASE).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.announcedVersion").value("0.5.0"))
                .andExpect(jsonPath("$.pendingAnnouncement").value(false));
    }

    @Test
    void missingKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void heartbeatCarriesTheAnnouncementForABehindClient() throws Exception {
        releases.updatePublishedVersion("0.4.0");
        releases.announce(List.of("Security fixes"), UpdateUrgency.URGENT);

        heartbeat("0.1.0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateAvailable").value(true))
                .andExpect(jsonPath("$.latestVersion").value("0.4.0"))
                .andExpect(jsonPath("$.announcement.version").value("0.4.0"))
                .andExpect(jsonPath("$.announcement.urgency").value("URGENT"))
                .andExpect(jsonPath("$.announcement.highlights[0]").value("Security fixes"));
    }

    @Test
    void heartbeatOmitsTheAnnouncementForAnUpToDateClient() throws Exception {
        releases.updatePublishedVersion("0.4.0");
        releases.announce(List.of("Security fixes"), UpdateUrgency.URGENT);

        heartbeat("0.4.0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateAvailable").value(false))
                .andExpect(jsonPath("$.announcement").doesNotExist());
    }

    private ResultActions heartbeat(String appVersion) throws Exception {
        return mockMvc.perform(post("/enduser/v1/internal/users/{u}/devices/heartbeat", UUID.randomUUID())
                .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"d1\",\"online\":true,\"stateHash\":\"h1\",\"appVersion\":\"" + appVersion + "\"}"));
    }
}
