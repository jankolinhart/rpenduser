package com.reelypops.rpenduser.membership;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for the B6 membership forwarder: it parses the report's {@code supportGroups[]} into the wire upsert
 * array (onAccount→igHandle, accountName→igAccount, raw {@code followingStatus}), falls back to the legacy boolean
 * {@code following} for an older client (true→following, false→unknown, never not_following), skips a group missing
 * either handle, and is best-effort (a client failure never propagates).
 */
class MembershipForwardingServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final SupportGroupMembershipClient client = mock(SupportGroupMembershipClient.class);
    private final MembershipForwardingService service = new MembershipForwardingService(client);

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<MembershipReportEntry> captureForwarded() {
        ArgumentCaptor<List<MembershipReportEntry>> body = ArgumentCaptor.forClass(List.class);
        verify(client).reportMemberships(eq(USER), body.capture());
        return body.getValue();
    }

    @Test
    void forwardsEachGroupMappingOnAccountToHandleAndAccountNameToAccount() {
        JsonNode report = json("""
                {"deviceId":"dev-1","stateHash":"h",
                 "supportGroups":[
                   {"sgId":1,"accountName":"grp.one","onAccount":"my.handle","followingStatus":"following"},
                   {"sgId":2,"accountName":"grp.two","onAccount":"other.handle","followingStatus":"not_following"}]}
                """);

        service.forward(USER, report);

        List<MembershipReportEntry> forwarded = captureForwarded();
        assertThat(forwarded).containsExactly(
                new MembershipReportEntry("my.handle", "grp.one", "following"),
                new MembershipReportEntry("other.handle", "grp.two", "not_following"));
    }

    @Test
    void forwardsUnknownAndRequestedVerbatim() {
        // These are NO-OPs downstream, but the relay must pass the raw signal through untouched — it never decides.
        JsonNode report = json("""
                {"supportGroups":[
                   {"accountName":"grp.one","onAccount":"h1","followingStatus":"unknown"},
                   {"accountName":"grp.two","onAccount":"h2","followingStatus":"requested"}]}
                """);

        service.forward(USER, report);

        assertThat(captureForwarded()).containsExactly(
                new MembershipReportEntry("h1", "grp.one", "unknown"),
                new MembershipReportEntry("h2", "grp.two", "requested"));
    }

    @Test
    void legacyBooleanTrueBecomesFollowing() {
        JsonNode report = json("""
                {"supportGroups":[{"accountName":"grp.one","onAccount":"my.handle","following":true}]}
                """);

        service.forward(USER, report);

        assertThat(captureForwarded()).containsExactly(new MembershipReportEntry("my.handle", "grp.one", "following"));
    }

    @Test
    void legacyBooleanFalseBecomesUnknownNeverNotFollowing() {
        // A legacy false is lossy (unknown and not_following both collapsed to it), so it must NOT flip stored
        // status downstream — it maps to unknown (a NO-OP), never to not_following.
        JsonNode report = json("""
                {"supportGroups":[{"accountName":"grp.one","onAccount":"my.handle","following":false}]}
                """);

        service.forward(USER, report);

        assertThat(captureForwarded()).containsExactly(new MembershipReportEntry("my.handle", "grp.one", "unknown"));
    }

    @Test
    void followingStatusStringWinsOverLegacyBoolean() {
        // A newer client sends both; the raw string is authoritative and the lossy boolean is ignored.
        JsonNode report = json("""
                {"supportGroups":[{"accountName":"grp.one","onAccount":"h","following":false,
                                   "followingStatus":"following"}]}
                """);

        service.forward(USER, report);

        assertThat(captureForwarded()).containsExactly(new MembershipReportEntry("h", "grp.one", "following"));
    }

    @Test
    void aGroupWithNeitherSignalForwardsANullStatus() {
        // Neither followingStatus nor following present → null status → omitted on the wire → NO-OP downstream.
        JsonNode report = json("""
                {"supportGroups":[{"accountName":"grp.one","onAccount":"my.handle"}]}
                """);

        service.forward(USER, report);

        assertThat(captureForwarded()).containsExactly(new MembershipReportEntry("my.handle", "grp.one", null));
    }

    @Test
    void anonBooleanLegacyFollowingIsIgnored() {
        // A malformed legacy "following" that is not a boolean (e.g. a string) is not a signal → null status.
        JsonNode report = json("""
                {"supportGroups":[{"accountName":"grp.one","onAccount":"h","following":"yes"}]}
                """);

        service.forward(USER, report);

        assertThat(captureForwarded()).containsExactly(new MembershipReportEntry("h", "grp.one", null));
    }

    @Test
    void skipsAGroupMissingItsHandleOrAccountOrWithBlankOnes() {
        // No onAccount, no accountName, blank onAccount, blank accountName, non-textual onAccount → all skipped;
        // only the well-formed last group survives.
        JsonNode report = json("""
                {"supportGroups":[
                   {"accountName":"grp.one","followingStatus":"following"},
                   {"onAccount":"h2","followingStatus":"following"},
                   {"accountName":"grp.three","onAccount":"   ","followingStatus":"following"},
                   {"accountName":"","onAccount":"h4","followingStatus":"following"},
                   {"accountName":"grp.five","onAccount":123,"followingStatus":"following"},
                   {"accountName":"grp.ok","onAccount":"h.ok","followingStatus":"following"}]}
                """);

        service.forward(USER, report);

        assertThat(captureForwarded()).containsExactly(new MembershipReportEntry("h.ok", "grp.ok", "following"));
    }

    @Test
    void doesNothingWhenThereIsNoSupportGroupsArray() {
        service.forward(USER, json("{\"deviceId\":\"d\",\"stateHash\":\"h\"}"));
        verifyNoInteractions(client);
    }

    @Test
    void doesNothingWhenSupportGroupsIsNotAnArray() {
        service.forward(USER, json("{\"supportGroups\":{\"accountName\":\"g\",\"onAccount\":\"h\"}}"));
        verifyNoInteractions(client);
    }

    @Test
    void doesNothingWhenEverySupportGroupIsSkipped() {
        // supportGroups[] present but empty after skipping → nothing to upsert → no call at all.
        service.forward(USER, json("{\"supportGroups\":[{\"accountName\":\"g\"},{\"onAccount\":\"h\"}]}"));
        verifyNoInteractions(client);
    }

    @Test
    void doesNothingWhenSupportGroupsIsEmpty() {
        service.forward(USER, json("{\"supportGroups\":[]}"));
        verifyNoInteractions(client);
    }

    @Test
    void swallowsAForwardingFailure() {
        // Best-effort: the report is already stored, so a rpsupportgroup outage must never fail report().
        doThrow(new RuntimeException("rpsupportgroup down")).when(client).reportMemberships(eq(USER), any());
        JsonNode report = json("""
                {"supportGroups":[{"accountName":"grp.one","onAccount":"my.handle","followingStatus":"following"}]}
                """);

        service.forward(USER, report); // must not throw

        verify(client).reportMemberships(eq(USER), any());
    }
}
