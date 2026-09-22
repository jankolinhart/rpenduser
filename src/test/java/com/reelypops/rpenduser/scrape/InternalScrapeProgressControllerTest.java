package com.reelypops.rpenduser.scrape;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>AFTER "START", THE SCREEN HAS TO SAY SOMETHING.</strong>
 *
 * <p>An operator can now start a deep scrape on a named machine. A deep scrape walks a whole tagged grid and
 * can run for many minutes, so a console that learns nothing afterwards leaves the operator with one move:
 * press start again. These rows are how the machine answers.</p>
 *
 * <p>Two properties are defended here. <b>Current state, not history</b> — a second report about the same
 * (machine, group) replaces the first, because the question is "what is it doing now", and a table that grew
 * a row per beat would answer a question nobody asked. And <b>a machine may only speak for itself</b>: the
 * customer comes from the token the BFF validated, so a report naming a machine that is not theirs is
 * refused rather than quietly creating a row about somebody else's computer.</p>
 */
@SpringBootTest(properties = {"rp.internal.api-key=test-internal-key", "rp.client.latest-version=9.9.9"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalScrapeProgressControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";
    private static final String REPORT = "/enduser/v1/internal/users/{userId}/devices/scrape-progress";
    private static final String READ = "/enduser/v1/internal/groups/{igAccount}/scrape-progress";

    @Autowired MockMvc mockMvc;

    private void registerDevice(UUID user, String deviceId) throws Exception {
        mockMvc.perform(post("/enduser/v1/internal/users/{userId}/devices", user)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"platform\":\"macOS 14.5\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private ResultActions report(UUID user, String body) throws Exception {
        return mockMvc.perform(post(REPORT, user).header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions report(UUID user, String deviceId, String group, String state, Integer scanned)
            throws Exception {
        return report(user, "{\"deviceId\":\"" + deviceId + "\",\"igAccount\":\"" + group
                + "\",\"state\":\"" + state + "\""
                + (scanned == null ? "" : ",\"scannedCount\":" + scanned) + "}");
    }

    @Test
    void aMachineReportsAndTheConsoleReadsItBack() throws Exception {
        UUID user = UUID.randomUUID();
        String group = "grp" + UUID.randomUUID().toString().substring(0, 8);
        registerDevice(user, "dev-a");

        report(user, "dev-a", group, "RUNNING", 41).andExpect(status().isNoContent());

        mockMvc.perform(get(READ, group).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deviceId").value("dev-a"))
                .andExpect(jsonPath("$[0].state").value("RUNNING"))
                .andExpect(jsonPath("$[0].scannedCount").value(41))
                .andExpect(jsonPath("$[0].updatedAt").exists());
    }

    /**
     * The count rises every few seconds for the length of a scrape. If each report added a row, a ten-minute
     * scrape would leave a hundred of them and the console would have to pick — which is the same bug as
     * deriving a fact twice, one report later.
     */
    @Test
    void asecondReportReplacesTheFirst_ratherThanAccumulating() throws Exception {
        UUID user = UUID.randomUUID();
        String group = "grp" + UUID.randomUUID().toString().substring(0, 8);
        registerDevice(user, "dev-a");

        report(user, "dev-a", group, "RUNNING", 10);
        report(user, "dev-a", group, "RUNNING", 250);
        report(user, "dev-a", group, "DONE", 412);

        mockMvc.perform(get(READ, group).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].state").value("DONE"))
                .andExpect(jsonPath("$[0].scannedCount").value(412));
    }

    /** "Who is scraping this group" is the console's actual question, and it spans machines and customers. */
    @Test
    void oneGroupCollectsEveryMachineWorkingIt() throws Exception {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        String group = "grp" + UUID.randomUUID().toString().substring(0, 8);
        registerDevice(one, "dev-one");
        registerDevice(two, "dev-two");

        report(one, "dev-one", group, "RUNNING", 5);
        report(two, "dev-two", group, "FAILED", null);

        mockMvc.perform(get(READ, group).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.length()").value(2));
    }

    /** A machine can scrape more than one group, so the row is per (machine, group) — not per machine. */
    @Test
    void oneMachineKeepsAGroupOfItsOwnForEachScrape() throws Exception {
        UUID user = UUID.randomUUID();
        String first = "grp" + UUID.randomUUID().toString().substring(0, 8);
        String second = "grp" + UUID.randomUUID().toString().substring(0, 8);
        registerDevice(user, "dev-a");

        report(user, "dev-a", first, "RUNNING", 3);
        report(user, "dev-a", second, "RUNNING", 7);

        mockMvc.perform(get(READ, first).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scannedCount").value(3));
        mockMvc.perform(get(READ, second).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].scannedCount").value(7));
    }

    /** The handle is a name people type in two cases; the console must not end up with two of the same group. */
    @Test
    void theGroupNameIsMatchedTheWayPeopleWriteIt() throws Exception {
        UUID user = UUID.randomUUID();
        String group = "grp" + UUID.randomUUID().toString().substring(0, 8);
        registerDevice(user, "dev-a");

        report(user, "dev-a", "  " + group.toUpperCase() + " ", "RUNNING", 9);

        mockMvc.perform(get(READ, group).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scannedCount").value(9));
    }

    /**
     * A report names a machine, and the customer comes from the token the BFF already validated. A machine
     * that is not theirs must be refused: accepting it would let one customer write progress about another's
     * computer, and the console would show it.
     */
    @Test
    void aMachineThatIsNotYoursCannotBeReportedOn() throws Exception {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        registerDevice(theirs, "dev-theirs");

        report(mine, "dev-theirs", "grp.one", "RUNNING", 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("No such device")));
    }

    /** A state the console cannot render is worse than no row: it would show a blank cell and look broken. */
    @Test
    void anUnknownStateIsRefused() throws Exception {
        UUID user = UUID.randomUUID();
        registerDevice(user, "dev-a");

        report(user, "dev-a", "grp.one", "SORT-OF-GOING", 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Unknown scrape state")));
    }

    /**
     * IDLE is deliberately not a state a machine may report: idleness is the ABSENT row. A machine that
     * reported idle and then closed would leave a row claiming it is idle forever, which is a claim about a
     * machine nobody is in touch with.
     */
    @Test
    void idleIsNotSomethingAMachineMayClaim() throws Exception {
        UUID user = UUID.randomUUID();
        registerDevice(user, "dev-a");

        report(user, "dev-a", "grp.one", "IDLE", 0).andExpect(status().isBadRequest());
        assertThat(ScrapeProgressService.STATES).doesNotContain("IDLE");
    }

    /**
     * STOPPED is its own ending: the operator asked, and it stopped. Not DONE — the grid was never reached —
     * and emphatically not FAILED, because an operator who cannot tell "I stopped it" from "it crashed" soon
     * stops reading the column at all.
     */
    @Test
    void stoppingIsAnEndingOfItsOwn_neitherFinishedNorBroken() throws Exception {
        UUID user = UUID.randomUUID();
        String group = "grp" + UUID.randomUUID().toString().substring(0, 8);
        registerDevice(user, "dev-a");

        report(user, "dev-a", group, "STOPPED", 63).andExpect(status().isNoContent());

        mockMvc.perform(get(READ, group).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].state").value("STOPPED"))
                .andExpect(jsonPath("$[0].scannedCount").value(63));
    }

    /** A group nothing has scraped is an empty list — the console says "no scrape reported", not an error. */
    @Test
    void aGroupNobodyHasScrapedIsEmpty_notAFailure() throws Exception {
        mockMvc.perform(get(READ, "grp.never-touched").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * A client's failure message is whatever the exception said, and an Instagram stack trace is long. The
     * column holds 500 characters, so an over-long message must cost the operator the tail of a sentence
     * rather than the whole report — losing the fact that it failed at all is the worse outcome by far.
     */
    @Test
    void anOverlongFailureMessageIsTrimmed_notRejected() throws Exception {
        UUID user = UUID.randomUUID();
        String group = "grp" + UUID.randomUUID().toString().substring(0, 8);
        registerDevice(user, "dev-a");

        report(user, "{\"deviceId\":\"dev-a\",\"igAccount\":\"" + group + "\",\"state\":\"FAILED\",\"message\":\""
                + "x".repeat(900) + "\"}")
                .andExpect(status().isNoContent());

        mockMvc.perform(get(READ, group).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].state").value("FAILED"))
                .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.hasLength(500)));
    }

    /** A blank message is no message: the console shows the state alone rather than an empty explanation. */
    @Test
    void aBlankMessageIsStoredAsNoMessage() throws Exception {
        UUID user = UUID.randomUUID();
        String group = "grp" + UUID.randomUUID().toString().substring(0, 8);
        registerDevice(user, "dev-a");

        report(user, "{\"deviceId\":\"dev-a\",\"igAccount\":\"" + group
                + "\",\"state\":\"DONE\",\"message\":\"   \"}")
                .andExpect(status().isNoContent());

        mockMvc.perform(get(READ, group).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].message").doesNotExist());
    }

    /** The whole point of DONE: the operator reads what it finished with, minutes after it ended. */
    @Test
    void aFinishedScrapeKeepsItsResultOnScreen() throws Exception {
        UUID user = UUID.randomUUID();
        String group = "grp" + UUID.randomUUID().toString().substring(0, 8);
        registerDevice(user, "dev-a");

        report(user, "{\"deviceId\":\"dev-a\",\"igAccount\":\"" + group + "\",\"state\":\"DONE\","
                + "\"scannedCount\":412,\"startedAt\":\"2026-09-22T10:00:00Z\","
                + "\"finishedAt\":\"2026-09-22T10:07:30Z\"}")
                .andExpect(status().isNoContent());

        mockMvc.perform(get(READ, group).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].startedAt").value("2026-09-22T10:00:00Z"))
                .andExpect(jsonPath("$[0].finishedAt").value("2026-09-22T10:07:30Z"));
    }
}
