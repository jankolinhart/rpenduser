package com.reelypops.rpenduser.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reelypops.rpenduser.drift.DriftForwardingService;
import com.reelypops.rpenduser.membership.MembershipForwardingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-level proof of the M5.1 {@code /report} wiring: alongside storing the snapshot and acknowledging the drift
 * that reached rpsupportgroup, the controller also forwards the report's memberships (B6). A bad body is rejected
 * before either forward runs.
 */
class InternalDeviceControllerReportHookTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final DeviceService devices = mock(DeviceService.class);
    private final ClientVersionService clientVersion = mock(ClientVersionService.class);
    private final DriftForwardingService driftForwarding = mock(DriftForwardingService.class);
    private final MembershipForwardingService membershipForwarding = mock(MembershipForwardingService.class);
    private final InternalDeviceController controller =
            new InternalDeviceController(devices, clientVersion, driftForwarding, membershipForwarding);

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reportForwardsMembershipsAndDrift() {
        JsonNode body = json("""
                {"deviceId":"dev-1","stateHash":"h",
                 "supportGroups":[{"accountName":"grp.one","onAccount":"my.handle","followingStatus":"following"}]}
                """);
        when(driftForwarding.forward(eq(USER), eq("dev-1"), eq(body))).thenReturn(List.of("k1"));

        var response = controller.report(USER, body);

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(response.getBody().acknowledgedDrift()).containsExactly("k1");
        verify(devices).applyReport(USER, "dev-1", body.toString(), "h");
        verify(membershipForwarding).forward(USER, body);
        verify(driftForwarding).forward(USER, "dev-1", body);
    }

    @Test
    void reportWithoutDeviceIdOrStateHashForwardsNothing() {
        var response = controller.report(USER, json("{\"stateHash\":\"h\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        verifyNoInteractions(membershipForwarding, driftForwarding);
        verify(devices, org.mockito.Mockito.never()).applyReport(any(), anyString(), anyString(), anyString());
    }
}
