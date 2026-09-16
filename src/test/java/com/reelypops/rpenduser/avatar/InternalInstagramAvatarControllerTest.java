package com.reelypops.rpenduser.avatar;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>A CUSTOMER'S OWN INSTAGRAM HANDLE HAS A FACE.</strong>
 *
 * <p>The desktop has had these pictures all along — it fetches each through the customer's own residential
 * Instagram session — and nothing ever sent one here, which is why the website's Seat Map drew a monogram
 * for every handle while the desktop drew a photograph.
 *
 * <p>What this file mostly pins is what the door REFUSES. It is the only route in this service that takes
 * arbitrary bytes from a client, and every refusal below is a screen that would otherwise show a broken
 * frame in place of a monogram that was designed to hold exactly that space.
 */
@SpringBootTest(properties = {"rp.internal.api-key=test-internal-key", "rp.client.latest-version=9.9.9"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalInstagramAvatarControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "test-internal-key";

    /** PNG's own magic bytes — what a sniffer looks at, so the fixture is a picture rather than a label. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Autowired MockMvc mockMvc;
    @Autowired InstagramAccountAvatarService avatars;

    private ResultActions contribute(UUID user, String handle, byte[] bytes, String type) throws Exception {
        return mockMvc.perform(put("/enduser/v1/internal/users/{u}/instagram-accounts/{h}/avatar", user, handle)
                .header(KEY_HEADER, KEY).contentType(type).content(bytes));
    }

    private ResultActions read(UUID user, String handle) throws Exception {
        return mockMvc.perform(get("/enduser/v1/internal/users/{u}/instagram-accounts/{h}/avatar", user, handle)
                .header(KEY_HEADER, KEY));
    }

    @Test
    void storesAPictureAndServesItBack() throws Exception {
        UUID user = UUID.randomUUID();

        contribute(user, "jean_marc.lestudio", PNG, "image/png").andExpect(status().isNoContent());

        read(user, "jean_marc.lestudio")
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(PNG));
    }

    /** A client re-contributes whenever Instagram's picture changes; the newest one wins, in place. */
    @Test
    void replacesThePictureRatherThanKeepingBoth() throws Exception {
        UUID user = UUID.randomUUID();
        contribute(user, "jm", PNG, "image/png").andExpect(status().isNoContent());
        byte[] newer = {'G', 'I', 'F', '8', '9', 'a'};

        contribute(user, "jm", newer, "image/gif").andExpect(status().isNoContent());

        read(user, "jm").andExpect(content().contentType(MediaType.IMAGE_GIF)).andExpect(content().bytes(newer));
    }

    /**
     * <strong>HANDLES ARE ONE CASE, ALWAYS.</strong> Instagram treats @Jean_Marc and @jean_marc as the same
     * account; storing them apart would give one handle two pictures and let the map draw whichever it found
     * first — which is the kind of fault that looks like a caching bug for a week.
     */
    @Test
    void treatsAHandleAsTheSameHandleWhateverItsCaseOrAt() throws Exception {
        UUID user = UUID.randomUUID();

        contribute(user, "Jean_Marc.LeStudio", PNG, "image/png").andExpect(status().isNoContent());

        read(user, "jean_marc.lestudio").andExpect(status().isOk()).andExpect(content().bytes(PNG));
    }

    /** No picture yet is an ORDINARY answer — a handle added a moment ago — and every reader draws a monogram. */
    @Test
    void answersNotFoundWhenNoClientHasContributedOne() throws Exception {
        read(UUID.randomUUID(), "never.seen").andExpect(status().isNotFound());
    }

    /**
     * <strong>ONE CUSTOMER'S PICTURE IS NOT ANOTHER'S.</strong> This is the whole reason these rows are here
     * rather than beside the support-group avatars, whose table has no user scoping because a group's picture
     * is shared. A handle's is not.
     */
    @Test
    void keepsOneCustomersPictureOutOfAnothers() throws Exception {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        contribute(mine, "shared.handle", PNG, "image/png").andExpect(status().isNoContent());

        read(theirs, "shared.handle").andExpect(status().isNotFound());
    }

    // ── what the door refuses ─────────────────────────────────────────────────────────────────────────

    @Test
    void refusesBytesThatDeclareThemselvesSomethingOtherThanAPicture() throws Exception {
        UUID user = UUID.randomUUID();

        contribute(user, "jm", "<html>".getBytes(), "text/html").andExpect(status().isBadRequest());

        read(user, "jm").andExpect(status().isNotFound());
    }

    /**
     * SVG IS A SCRIPT. It declares itself an image and a browser will run what is inside it, so it is
     * refused by name — which is why the check is a LIST of what we serve rather than a prefix on "image/".
     */
    @Test
    void refusesAnSvgEvenThoughItCallsItselfAnImage() throws Exception {
        contribute(UUID.randomUUID(), "jm", "<svg/>".getBytes(), "image/svg+xml")
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnEmptyBody() throws Exception {
        contribute(UUID.randomUUID(), "jm", new byte[0], "image/png").andExpect(status().isBadRequest());
    }

    @Test
    void refusesSomethingTooBigToBeAProfilePicture() throws Exception {
        byte[] huge = new byte[InstagramAccountAvatarService.MAX_BYTES + 1];
        huge[0] = (byte) 0x89;

        contribute(UUID.randomUUID(), "jm", huge, "image/png").andExpect(status().isBadRequest());
    }

    /**
     * A content type with parameters is still that type, so the check compares the TYPE and not the header.
     *
     * <p>Written first as {@code charset=binary}, which Spring refuses with a 415 before any of this code
     * runs — an invalid charset name, not a rejection of parameters. A benign parameter does arrive, so the
     * stripping is reachable and this is what reaches it.
     */
    @Test
    void acceptsAContentTypeThatCarriesParameters() throws Exception {
        UUID user = UUID.randomUUID();

        contribute(user, "jm", PNG, "image/png;version=1").andExpect(status().isNoContent());

        read(user, "jm").andExpect(status().isOk());
    }

    /**
     * PRIVATE, and cached. It belongs to one customer, so a shared cache must never hold it; and it changes
     * about never, on a screen that re-reads whenever anything about the plan changes.
     */
    @Test
    void servesItPrivatelyAndLetsTheReaderCacheIt() throws Exception {
        UUID user = UUID.randomUUID();
        contribute(user, "jm", PNG, "image/png").andExpect(status().isNoContent());

        read(user, "jm").andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")));
    }

    /** rpenduser is off the internet: no key, no answer — in either direction. */
    @Test
    void refusesACallerWithoutTheInternalKey() throws Exception {
        mockMvc.perform(get("/enduser/v1/internal/users/{u}/instagram-accounts/jm/avatar", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/enduser/v1/internal/users/{u}/instagram-accounts/jm/avatar", UUID.randomUUID())
                        .contentType("image/png").content(PNG))
                .andExpect(status().isUnauthorized());
    }

    /** Nothing stored means nothing to leak: a refused contribution must not half-write a row. */
    @Test
    void storesNothingWhenTheContributionIsRefused() throws Exception {
        UUID user = UUID.randomUUID();
        contribute(user, "jm", PNG, "image/png").andExpect(status().isNoContent());

        contribute(user, "jm", "<html>".getBytes(), "text/html").andExpect(status().isBadRequest());

        // ...and the picture it already had is untouched.
        read(user, "jm").andExpect(status().isOk()).andExpect(content().bytes(PNG));
    }

    /**
     * The service is a public door, and a path variable can never be null — so this branch is unreachable
     * through the controller and reachable by any other caller. It stores nothing and says so, rather than
     * throwing: "you did not tell me which handle" is a refusal, not a crash.
     */
    @Test
    void storesNothingForACallerThatNamesNoHandle() {
        assertThat(avatars.put(UUID.randomUUID(), null, PNG, "image/png")).isFalse();
        assertThat(avatars.put(UUID.randomUUID(), "   ", PNG, "image/png")).isFalse();
    }

    @Test
    void assertsWhatItWillServe() {
        assertThat(InstagramAccountAvatarService.SERVABLE)
                .containsExactlyInAnyOrder("image/jpeg", "image/png", "image/webp", "image/gif");
    }
}
