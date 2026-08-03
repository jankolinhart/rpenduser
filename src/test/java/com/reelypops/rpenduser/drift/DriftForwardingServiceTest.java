package com.reelypops.rpenduser.drift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for the M5 re-vet drift forwarder: it resolves each drift's client-local {@code sgId} to an IG account
 * via the report's own {@code supportGroups[]}, maps the client kind onto the rpsupportgroup {@code DriftKind} name,
 * skips anything unresolvable/malformed, and is best-effort (a client failure never propagates).
 */
class DriftForwardingServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final SupportGroupDriftClient client = mock(SupportGroupDriftClient.class);
    private final DriftForwardingService service = new DriftForwardingService(client);

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void forwardsEachDriftResolvingSgIdToItsIgAccount() {
        JsonNode report = json("""
                {"deviceId":"dev-1","stateHash":"h",
                 "supportGroups":[{"sgId":1,"accountName":"grp.one"},{"sgId":2,"accountName":"grp.two"}],
                 "drift":[
                   {"sgId":1,"kind":"marker-disagree","agreePass":5,"disagreePass":2,"persistenceCount":4},
                   {"sgId":2,"kind":"new-owner","nominatedOwnerHandle":"cand.owner"}]}
                """);

        service.forward(USER, "dev-1", report);

        ArgumentCaptor<String> ig = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DriftReportRequest> body = ArgumentCaptor.forClass(DriftReportRequest.class);
        verify(client, times(2)).reportDrift(ig.capture(), body.capture());

        assertThat(ig.getAllValues()).containsExactly("grp.one", "grp.two");
        DriftReportRequest markerDisagree = body.getAllValues().get(0);
        assertThat(markerDisagree.kind()).isEqualTo("MARKER_DISAGREE");
        assertThat(markerDisagree.reporterDeviceId()).isEqualTo("dev-1");
        assertThat(markerDisagree.reporterUserId()).isEqualTo(USER);
        assertThat(markerDisagree.agreePass()).isEqualTo(5);
        assertThat(markerDisagree.disagreePass()).isEqualTo(2);
        assertThat(markerDisagree.persistenceCount()).isEqualTo(4);
        assertThat(markerDisagree.nominatedOwnerHandle()).isNull();
        DriftReportRequest newOwner = body.getAllValues().get(1);
        assertThat(newOwner.kind()).isEqualTo("NEW_OWNER");
        assertThat(newOwner.nominatedOwnerHandle()).isEqualTo("cand.owner");
        assertThat(newOwner.agreePass()).isNull();
    }

    @Test
    void skipsDriftWhoseSgIdResolvesToNoAccount() {
        // No supportGroups[] at all → empty map → a non-null sgId still resolves to nothing.
        service.forward(USER, "dev-1", json("{\"drift\":[{\"sgId\":9,\"kind\":\"marker-disagree\"}]}"));
        verifyNoInteractions(client);
    }

    @Test
    void skipsDriftWithoutAnSgId() {
        service.forward(USER, "dev-1", json("""
                {"supportGroups":[{"sgId":1,"accountName":"grp.one"}],"drift":[{"kind":"marker-disagree"}]}
                """));
        verifyNoInteractions(client);
    }

    @Test
    void skipsDriftWithAnUnrecognisedOrMissingKind() {
        service.forward(USER, "dev-1", json("""
                {"supportGroups":[{"sgId":1,"accountName":"grp.one"}],
                 "drift":[{"sgId":1,"kind":"mystery"},{"sgId":1}]}
                """));
        verifyNoInteractions(client);
    }

    @Test
    void doesNothingWhenThereIsNoDriftArray() {
        service.forward(USER, "dev-1", json("{\"supportGroups\":[{\"sgId\":1,\"accountName\":\"g\"}]}"));
        verifyNoInteractions(client);
    }

    @Test
    void doesNothingWhenTheDriftArrayIsEmpty() {
        service.forward(USER, "dev-1", json("{\"drift\":[]}"));
        verifyNoInteractions(client);
    }

    @Test
    void doesNothingWhenDriftIsNotAnArray() {
        service.forward(USER, "dev-1", json("{\"drift\":{\"sgId\":1}}"));
        verifyNoInteractions(client);
    }

    @Test
    void ignoresMalformedSupportGroupsWhenResolving() {
        // supportGroups is not an array → empty map → unresolvable.
        service.forward(USER, "dev-1",
                json("{\"supportGroups\":{},\"drift\":[{\"sgId\":1,\"kind\":\"new-owner\",\"nominatedOwnerHandle\":\"c\"}]}"));
        // entries missing sgId or accountName are ignored → still unresolvable.
        service.forward(USER, "dev-1", json("""
                {"supportGroups":[{"accountName":"no-id"},{"sgId":2}],
                 "drift":[{"sgId":2,"kind":"new-owner","nominatedOwnerHandle":"c"}]}
                """));
        verifyNoInteractions(client);
    }

    @Test
    void swallowsAForwardingFailureAndContinuesWithTheRest() {
        doThrow(new RuntimeException("rpsupportgroup down")).when(client).reportDrift(eq("grp.one"), any());
        JsonNode report = json("""
                {"supportGroups":[{"sgId":1,"accountName":"grp.one"},{"sgId":2,"accountName":"grp.two"}],
                 "drift":[{"sgId":1,"kind":"marker-disagree"},{"sgId":2,"kind":"marker-disagree"}]}
                """);

        service.forward(USER, "dev-1", report);   // must not throw

        verify(client).reportDrift(eq("grp.one"), any());   // attempted
        verify(client).reportDrift(eq("grp.two"), any());   // and continued past the failure
    }
}
