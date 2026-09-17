package com.reelypops.rpenduser.instagram;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>AN INSTAGRAM ACCOUNT IS A THING THE CLOUD KNOWS ABOUT.</strong>
 *
 * <p>Operator, 16/09/2026: "any change to it, adding/removing needs to be registered in the cloud and
 * refused at the client if the limit does not allow it."
 *
 * <p>Until these rows existed the cloud learned a handle only SIDEWAYS — when it joined a support group, or
 * when a machine reported it in focus. So {@code igaccounts.max} bit at "joins its first group" rather than
 * at "adds an account", and a customer on a one-account plan could configure five and be refused by nothing.
 *
 * <p>CLAIMED, NOT OBSERVED, is the property most of this file defends: a row is what the user DID, and
 * nothing a machine merely saw may create or destroy one.
 */
@SpringBootTest(properties = {"rp.internal.api-key=test-internal-key", "rp.client.latest-version=9.9.9"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalInstagramAccountControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";
    private static final String PATH = "/enduser/v1/internal/users/{userId}/instagram-accounts";

    @Autowired MockMvc mockMvc;
    @Autowired InstagramAccountRepository accounts;
    @Autowired InstagramAccountService service;

    private ResultActions claim(UUID user, String handle, String deviceId) throws Exception {
        String body = deviceId == null
                ? "{\"igHandle\":\"" + handle + "\"}"
                : "{\"igHandle\":\"" + handle + "\",\"deviceId\":\"" + deviceId + "\"}";
        return mockMvc.perform(post(PATH, user).header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions release(UUID user, String handle) throws Exception {
        return mockMvc.perform(delete(PATH + "/{h}", user, handle).header(KEY_HEADER, KEY));
    }

    private ResultActions held(UUID user) throws Exception {
        return mockMvc.perform(get(PATH, user).header(KEY_HEADER, KEY));
    }

    @Test
    void takesAnAccountOnAndCountsIt() throws Exception {
        UUID user = UUID.randomUUID();

        claim(user, "jean_marc.lestudio", "mac-1").andExpect(status().isCreated());

        held(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].igHandle").value("jean_marc.lestudio"))
                .andExpect(jsonPath("$[0].deviceId").value("mac-1"))
                .andExpect(jsonPath("$[0].claimedAt").isNotEmpty());
    }

    /**
     * <strong>CLAIMING IS IDEMPOTENT, AND THE STATUS SAYS WHICH IT WAS.</strong> 201 created a row and spent
     * a slot; 200 confirmed one already held. A retry after a lost response must never read as a refusal —
     * the one sentence this must not show wrongly is "you are out of room".
     */
    @Test
    void confirmsAnAccountAlreadyHeldRatherThanRefusingIt() throws Exception {
        UUID user = UUID.randomUUID();
        claim(user, "jm", "mac-1").andExpect(status().isCreated());

        claim(user, "jm", "mac-1").andExpect(status().isOk());

        assertThat(accounts.findByUserIdOrderByClaimedAtAsc(user)).hasSize(1);
    }

    /** A re-claim refreshes WHERE it was last seen — an account moves between a customer's machines. */
    @Test
    void refreshesWhereItWasLastSeenWithoutMovingWhenItBecameTheirs() throws Exception {
        UUID user = UUID.randomUUID();
        claim(user, "jm", "mac-1").andExpect(status().isCreated());
        var first = accounts.findByUserIdAndIgHandle(user, "jm").orElseThrow().getClaimedAt();

        claim(user, "jm", "pc-2").andExpect(status().isOk());

        var after = accounts.findByUserIdAndIgHandle(user, "jm").orElseThrow();
        assertThat(after.getDeviceId()).isEqualTo("pc-2");
        // THE DATE IT BECAME THEIRS HAS NOT CHANGED. Moving it would make "since when" answer the wrong
        // question on every screen that asks.
        assertThat(after.getClaimedAt()).isEqualTo(first);
    }

    /**
     * <strong>HANDLES ARE ONE CASE, ALWAYS.</strong> Instagram treats @Jean_Marc and @jean_marc as one
     * account; storing them apart would let a customer hold the same account twice and then be refused by a
     * ceiling they are nowhere near.
     */
    @Test
    void treatsAHandleAsTheSameHandleWhateverItsCaseOrAt() throws Exception {
        UUID user = UUID.randomUUID();
        claim(user, "Jean_Marc.LeStudio", null).andExpect(status().isCreated());

        claim(user, "@jean_marc.lestudio", null).andExpect(status().isOk());

        assertThat(accounts.findByUserIdOrderByClaimedAtAsc(user)).hasSize(1);
    }

    @Test
    void givesAnAccountUp() throws Exception {
        UUID user = UUID.randomUUID();
        claim(user, "jm", null).andExpect(status().isCreated());

        release(user, "jm").andExpect(status().isNoContent());

        held(user).andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * RELEASING ONE NOT HELD IS NOT A FAULT. It is what a retry looks like, and what a client tidying up
     * after itself looks like — a 404 would make a caller treat a finished job as an unfinished one.
     */
    @Test
    void releasingSomethingNotHeldIsQuietlyFine() throws Exception {
        release(UUID.randomUUID(), "never.had.it").andExpect(status().isNoContent());
    }

    @Test
    void releasesByTheSameHandleWhateverItsCase() throws Exception {
        UUID user = UUID.randomUUID();
        claim(user, "jm", null).andExpect(status().isCreated());

        release(user, "JM").andExpect(status().isNoContent());

        assertThat(accounts.findByUserIdOrderByClaimedAtAsc(user)).isEmpty();
    }

    /** One customer's accounts are their own — the count a ceiling is enforced against must be theirs alone. */
    @Test
    void keepsOneCustomersAccountsOutOfAnothers() throws Exception {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        claim(mine, "shared.handle", null).andExpect(status().isCreated());

        held(theirs).andExpect(jsonPath("$.length()").value(0));
        // ...and the same handle is a SEPARATE claim for somebody else, not a conflict.
        claim(theirs, "shared.handle", null).andExpect(status().isCreated());
    }

    @Test
    void countsEveryAccountAClaimHasTakenOn() throws Exception {
        UUID user = UUID.randomUUID();
        claim(user, "one", null).andExpect(status().isCreated());
        claim(user, "two", null).andExpect(status().isCreated());
        claim(user, "three", null).andExpect(status().isCreated());

        held(user).andExpect(jsonPath("$.length()").value(3));
    }

    /** Oldest first, so two readers never disagree about which account is "the first". */
    @Test
    void answersInTheOrderTheyWereTakenOn() throws Exception {
        UUID user = UUID.randomUUID();
        claim(user, "first", null).andExpect(status().isCreated());
        claim(user, "second", null).andExpect(status().isCreated());

        held(user).andExpect(jsonPath("$[0].igHandle").value("first"))
                .andExpect(jsonPath("$[1].igHandle").value("second"));
    }

    /** A machine is a HINT, not part of the identity: an account survives the computer it was added on. */
    @Test
    void takesAnAccountOnWithNoMachineNamed() throws Exception {
        UUID user = UUID.randomUUID();

        claim(user, "jm", null).andExpect(status().isCreated());

        held(user).andExpect(jsonPath("$[0].deviceId").doesNotExist());
    }

    @Test
    void refusesAClaimThatNamesNoAccount() throws Exception {
        UUID user = UUID.randomUUID();

        mockMvc.perform(post(PATH, user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"mac-1\"}"))
                .andExpect(status().isBadRequest());
        claim(user, "   ", null).andExpect(status().isBadRequest());

        assertThat(accounts.findByUserIdOrderByClaimedAtAsc(user)).isEmpty();
    }

    /**
     * The service is a public door and a path variable can never be null, so these two branches are
     * unreachable through the controller and reachable by any other caller. Claiming nothing takes nothing
     * on, and releasing nothing gives nothing up — both say so rather than throwing, because "you did not
     * tell me which account" is a refusal, not a crash.
     */
    @Test
    void takesNothingOnForACallerThatNamesNoAccount() {
        UUID user = UUID.randomUUID();

        assertThat(service.claim(user, null, "mac-1")).isFalse();
        assertThat(service.claim(user, "  ", "mac-1")).isFalse();
        assertThat(service.release(user, null)).isFalse();
        assertThat(service.release(user, "")).isFalse();

        assertThat(accounts.findByUserIdOrderByClaimedAtAsc(user)).isEmpty();
    }

    /** rpenduser is off the internet: no key, no answer, in any direction. */
    @Test
    void refusesACallerWithoutTheInternalKey() throws Exception {
        UUID user = UUID.randomUUID();
        mockMvc.perform(get(PATH, user)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH, user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"igHandle\":\"jm\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(PATH + "/{h}", user, "jm")).andExpect(status().isUnauthorized());
    }
}
