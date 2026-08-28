package com.reelypops.rpenduser.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reelypops.rpenduser.drift.DriftForwardingService;
import com.reelypops.rpenduser.membership.MembershipForwardingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
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

    // --- WHO HOLDS THIS HANDLE (28/08/2026) ------------------------------------------------------------------
    //
    // Two devices on one Instagram account do the same work twice at twice the like-rate. The client already
    // reports its in-focus handle; these lock what the cloud says back on the reply it was already getting.

    @Test
    void theReplyNamesTheMachineHoldingTheHandle() {
        JsonNode body = json("""
                {"deviceId":"dev-1","stateHash":"h",
                 "igAccounts":[{"handle":"shared_one","inFocus":true,"sessionValid":true}]}
                """);
        Device mine = Device.register(USER, "dev-1", "mac");
        mine.setFocusedHandle("shared_one");
        when(devices.applyReport(eq(USER), eq("dev-1"), anyString(), eq("h"))).thenReturn(mine);
        Device incumbent = Device.register(USER, "dev-2", "windows");
        incumbent.setFocusedHandle("shared_one");
        when(devices.holderOf(USER, "shared_one")).thenReturn(Optional.of(incumbent));

        var response = controller.report(USER, body);

        // Named, with what it runs on and when it was last heard from: "nothing is moving" must never be a
        // mystery — that is what stops the user reading a held handle as a fault.
        assertThat(response.getBody().handleHeldBy().deviceId()).isEqualTo("dev-2");
        assertThat(response.getBody().handleHeldBy().platform()).isEqualTo("windows");
        assertThat(response.getBody().handleHeldBy().lastSeenAt()).isNotNull();
    }

    @Test
    void aDeviceIsNeverToldItIsBlockingItself() {
        JsonNode body = json("""
                {"deviceId":"dev-1","stateHash":"h",
                 "igAccounts":[{"handle":"mine_alone","inFocus":true,"sessionValid":true}]}
                """);
        Device mine = Device.register(USER, "dev-1", "mac");
        mine.setFocusedHandle("mine_alone");
        when(devices.applyReport(eq(USER), eq("dev-1"), anyString(), eq("h"))).thenReturn(mine);
        when(devices.holderOf(USER, "mine_alone")).thenReturn(Optional.of(mine));

        assertThat(controller.report(USER, body).getBody().handleHeldBy()).isNull();
    }

    @Test
    void theReplyIsSilentWhenThisDeviceClaimsNothing() {
        // No in-focus account means no claim, so the holder is never even looked up — a device working nothing
        // must not read as conflicting with another device working nothing.
        JsonNode body = json("""
                {"deviceId":"dev-1","stateHash":"h",
                 "igAccounts":[{"handle":"idle_one","inFocus":false,"sessionValid":true}]}
                """);
        when(devices.applyReport(eq(USER), eq("dev-1"), anyString(), eq("h")))
                .thenReturn(Device.register(USER, "dev-1", "mac"));

        assertThat(controller.report(USER, body).getBody().handleHeldBy()).isNull();
        verify(devices, org.mockito.Mockito.never()).holderOf(any(), anyString());
    }

    @Test
    void aFailedHolderLookupNeverFailsTheReport() {
        // THE RULE THAT MATTERS. The snapshot is already stored by the time the lookup runs, so a fault there
        // must not turn a call whose work is done into a 500 — the discipline the membership forward follows.
        // The client simply hears no holder, which is the fail-OPEN direction: absence never stops anything.
        JsonNode body = json("""
                {"deviceId":"dev-1","stateHash":"h",
                 "igAccounts":[{"handle":"shared_one","inFocus":true,"sessionValid":true}]}
                """);
        Device mine = Device.register(USER, "dev-1", "mac");
        mine.setFocusedHandle("shared_one");
        when(devices.applyReport(eq(USER), eq("dev-1"), anyString(), eq("h"))).thenReturn(mine);
        when(devices.holderOf(USER, "shared_one"))
                .thenThrow(new IllegalStateException("database is having a moment"));

        var response = controller.report(USER, body);

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(response.getBody().handleHeldBy()).isNull();
    }

    @Test
    void takeoverNamesWhoYielded_soTheCallerKnowsToWaitBeforeItStarts() {
        when(devices.takeOverHandle(USER, "dev-1", "shared_one")).thenReturn(List.of("dev-2"));

        var response = controller.takeOverHandle(USER, "dev-1", json("{\"handle\":\"shared_one\"}"));

        assertThat(response.getBody().yieldedBy()).containsExactly("dev-2");
    }

    @Test
    void takeoverWithoutAHandleIsABadRequest_neverASilentNoOp() {
        assertThat(controller.takeOverHandle(USER, "dev-1", json("{}")).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(devices);
    }
}
