package com.reelypops.rpenduser.standing;

import com.reelypops.rpenduser.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>THE OVER-THE-PLAN CLOCKS</strong> — the start of the seven days somebody gets to choose what to
 * give up (operator, 16/09/2026: <i>"we give them a grace period of 7 days with daily reminders ... after 7
 * days we force them with an overlay to chose"</i>).
 *
 * <p>Every property here exists because getting it wrong produces a screen that looks correct. A clock
 * re-stamped on each read sits at day zero for ever and the seventh day never arrives; a clock that is not
 * cleared when somebody comes back inside their plan counts down against a ceiling they are no longer over;
 * a clock per account rather than per pool gives the seat taken today the deadline of last week's group.
 * None of those fail anywhere — they just quietly never do, or do the wrong thing on a Tuesday.
 */
@SpringBootTest(properties = {"rp.internal.api-key=test-internal-key", "rp.client.latest-version=9.9.9"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalOverPlanControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    OverPlanMarkRepository marks;

    private ResultActions observe(UUID user, String body) throws Exception {
        return mockMvc.perform(post("/enduser/v1/internal/users/{userId}/over-plan", user).header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions observing(UUID user, String... pools) throws Exception {
        String list = String.join(",", List.of(pools).stream().map(p -> "\"" + p + "\"").toList());
        return observe(user, "{\"over\":[" + list + "]}");
    }

    @Test
    void startsAClockForAPoolThatIsOver() throws Exception {
        UUID user = UUID.randomUUID();

        observing(user, "GROUP_CONNECTIONS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marks.length()").value(1))
                .andExpect(jsonPath("$.marks[0].pool").value("GROUP_CONNECTIONS"))
                .andExpect(jsonPath("$.marks[0].since").exists());
    }

    /**
     * <strong>THE ONE THAT MATTERS.</strong> The BFF calls this on every plan-standing read — every app
     * start, every browser session. A stamp rewritten on each of those would hold every account at day zero
     * for ever, and the seventh day would arrive for nobody. It would look exactly like the feature working.
     */
    @Test
    void neverRestampsAClockThatIsAlreadyRunning() throws Exception {
        UUID user = UUID.randomUUID();
        observing(user, "GROUP_CONNECTIONS").andExpect(status().isOk());
        Instant first = marks.findByUserId(user).getFirst().getSince();

        observing(user, "GROUP_CONNECTIONS").andExpect(status().isOk());
        observing(user, "GROUP_CONNECTIONS").andExpect(status().isOk());

        assertThat(marks.findByUserId(user)).singleElement()
                .extracting(OverPlanMark::getSince).isEqualTo(first);
    }

    /** ...and repeating the observation does not create a second clock for the same pool either. */
    @Test
    void repeatingAnObservationCreatesNoSecondClock() throws Exception {
        UUID user = UUID.randomUUID();

        observing(user, "SEATS").andExpect(status().isOk());
        observing(user, "SEATS").andExpect(jsonPath("$.marks.length()").value(1));

        assertThat(marks.findByUserId(user)).hasSize(1);
    }

    /**
     * Coming back inside the plan CLEARS the clock rather than pausing it. Somebody who goes over again
     * later is making a fresh decision, and gets a fresh seven days to make it.
     */
    @Test
    void clearsAClockWhenThePoolIsInsideItsCeilingAgain() throws Exception {
        UUID user = UUID.randomUUID();
        observing(user, "GROUP_CONNECTIONS").andExpect(status().isOk());

        observing(user).andExpect(status().isOk()).andExpect(jsonPath("$.marks.length()").value(0));

        assertThat(marks.findByUserId(user)).isEmpty();
    }

    @Test
    void goingOverAgainAfterComingBackInsideStartsAFreshClock() throws Exception {
        UUID user = UUID.randomUUID();
        observing(user, "SEATS").andExpect(status().isOk());
        Instant first = marks.findByUserId(user).getFirst().getSince();
        observing(user).andExpect(status().isOk());

        observing(user, "SEATS").andExpect(status().isOk());

        assertThat(marks.findByUserId(user)).singleElement()
                .extracting(OverPlanMark::getSince)
                .satisfies(since -> assertThat((Instant) since).isAfterOrEqualTo(first));
    }

    /**
     * <strong>PER POOL, SEPARATELY</strong> (the operator's ruling). Three independent ceilings counted three
     * different ways, so three independent deadlines — the seat taken on Tuesday must not inherit the
     * deadline of the group joined last week.
     */
    @Test
    void runsAClockPerPoolAndClearsThemOneAtATime() throws Exception {
        UUID user = UUID.randomUUID();
        observing(user, "SEATS", "GROUP_CONNECTIONS").andExpect(jsonPath("$.marks.length()").value(2));
        Instant seats = marks.findByUserId(user).stream()
                .filter(m -> m.getPool() == PlanPool.SEATS).findFirst().orElseThrow().getSince();

        observing(user, "SEATS")
                .andExpect(jsonPath("$.marks.length()").value(1))
                .andExpect(jsonPath("$.marks[0].pool").value("SEATS"));

        // The surviving clock kept running; only the other one was cleared.
        assertThat(marks.findByUserId(user)).singleElement()
                .extracting(OverPlanMark::getSince).isEqualTo(seats);
    }

    /** The answer is in a stable order, so two readers never disagree about which clock is "the first". */
    @Test
    void answersInPoolOrder() throws Exception {
        UUID user = UUID.randomUUID();

        observing(user, "GROUP_CONNECTIONS", "SEATS", "IG_ACCOUNTS")
                .andExpect(jsonPath("$.marks[0].pool").value("SEATS"))
                .andExpect(jsonPath("$.marks[1].pool").value("IG_ACCOUNTS"))
                .andExpect(jsonPath("$.marks[2].pool").value("GROUP_CONNECTIONS"));
    }

    /**
     * ...and in pool order even when some clocks were already running and some are new, which is the only
     * case that can come out wrong. The test above passes with no sorting at all: every mark in it is new,
     * and new ones are built by walking the pools in their own order, so they arrive sorted by accident.
     * Here the OLDER clock belongs to the LATER pool, so unsorted output is observably backwards.
     */
    @Test
    void answersInPoolOrderWhenOldAndNewClocksAreMixed() throws Exception {
        UUID user = UUID.randomUUID();
        observing(user, "GROUP_CONNECTIONS").andExpect(status().isOk());

        observing(user, "GROUP_CONNECTIONS", "SEATS")
                .andExpect(jsonPath("$.marks[0].pool").value("SEATS"))
                .andExpect(jsonPath("$.marks[1].pool").value("GROUP_CONNECTIONS"));
    }

    /**
     * An absent or empty list is "nothing is over", never "I have no opinion" — and it therefore CLEARS.
     * The alternative fails open: somebody counts down against a ceiling they are no longer over.
     */
    @Test
    void treatsAnAbsentListAsNothingBeingOver() throws Exception {
        UUID user = UUID.randomUUID();
        observing(user, "SEATS").andExpect(status().isOk());

        observe(user, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.marks.length()").value(0));

        assertThat(marks.findByUserId(user)).isEmpty();
    }

    @Test
    void treatsNoBodyAtAllTheSameWay() throws Exception {
        UUID user = UUID.randomUUID();
        observing(user, "SEATS").andExpect(status().isOk());

        mockMvc.perform(post("/enduser/v1/internal/users/{userId}/over-plan", user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marks.length()").value(0));

        assertThat(marks.findByUserId(user)).isEmpty();
    }

    /** One account's clocks are its own — an observation for one user never touches another's. */
    @Test
    void keepsOneAccountsClocksOutOfAnothers() throws Exception {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        observing(one, "SEATS").andExpect(status().isOk());

        observing(two).andExpect(status().isOk());

        assertThat(marks.findByUserId(one)).hasSize(1);
    }

    /** rpenduser is off the internet: no key, no answer. */
    @Test
    void refusesACallerWithoutTheInternalKey() throws Exception {
        mockMvc.perform(post("/enduser/v1/internal/users/{userId}/over-plan", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"over\":[\"SEATS\"]}"))
                .andExpect(status().isUnauthorized());
    }
}
