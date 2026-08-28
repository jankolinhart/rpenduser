package com.reelypops.rpenduser.device;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ABSENCE MUST NOT BECOME A CONFLICT.
 *
 * <p>Two devices that have each reported nothing in focus are not fighting over an account — they are both
 * idle. If a blank or null handle reached the query it would match every other device that also stored one,
 * and the cloud would tell two idle machines they were colliding.
 *
 * <p>So the guard sits in front of the repository, not inside it: the query is never even asked.
 */
class DeviceServiceClaimTest {

    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceService service = new DeviceService(devices);

    @Test
    void asksTheRepositoryForTheUsersOtherDevicesOnThatHandle() {
        Device rival = Device.register(USER, "dev-2", "win");
        when(devices.findByUserIdAndFocusedHandleAndDeviceIdNot(USER, "shared_one", "dev-1"))
                .thenReturn(List.of(rival));

        assertThat(service.claimingSameHandle(USER, "dev-1", "shared_one")).containsExactly(rival);
    }

    @Test
    void normalisesTheHandleItAsksAbout_soCaseAndPaddingCannotHideAConflict() {
        // The stored side is normalised by Device#setFocusedHandle; this is the other half of that promise.
        service.claimingSameHandle(USER, "dev-1", "  Shared_One  ");

        verify(devices).findByUserIdAndFocusedHandleAndDeviceIdNot(USER, "shared_one", "dev-1");
    }

    @Test
    void neverAsksAtAllWhenThereIsNoHandleToAskAbout() {
        assertThat(service.claimingSameHandle(USER, "dev-1", null)).isEmpty();
        assertThat(service.claimingSameHandle(USER, "dev-1", "   ")).isEmpty();

        verify(devices, never()).findByUserIdAndFocusedHandleAndDeviceIdNot(any(), anyString(), anyString());
    }

    // --- WHO HOLDS IT: the incumbent, while live ------------------------------------------------------------

    @Test
    void theIncumbentHolds_soASecondMachineCannotInterruptARoundInFlight() {
        Device incumbent = seen("dev-1", "mac", "shared_one", Instant.now().minusSeconds(90));
        Device challenger = seen("dev-2", "win", "shared_one", Instant.now().minusSeconds(60));
        // Both were heard from within the TTL; the elder CLAIM wins, not the fresher heartbeat.
        stamp(incumbent, Instant.now().minusSeconds(3600));
        stamp(challenger, Instant.now().minusSeconds(120));
        when(devices.findByUserIdAndFocusedHandle(USER, "shared_one"))
                .thenReturn(List.of(challenger, incumbent));

        assertThat(service.holderOf(USER, "shared_one")).contains(incumbent);
    }

    @Test
    void aQuietClaimantHoldsNothing_soAClosedLaptopCannotFreezeTheAccount() {
        // Six minutes unheard, past the five-minute TTL. It keeps its claim row; it stops BLOCKING.
        Device gone = seen("dev-1", "mac", "shared_one", Instant.now().minus(Duration.ofMinutes(6)));
        stamp(gone, Instant.now().minusSeconds(3600));
        Device live = seen("dev-2", "win", "shared_one", Instant.now().minusSeconds(30));
        stamp(live, Instant.now().minusSeconds(120));
        when(devices.findByUserIdAndFocusedHandle(USER, "shared_one")).thenReturn(List.of(gone, live));

        assertThat(service.holderOf(USER, "shared_one")).contains(live);
    }

    @Test
    void nobodyHoldsAHandleWhenEveryClaimantHasGoneQuiet() {
        Device gone = seen("dev-1", "mac", "shared_one", Instant.now().minus(Duration.ofMinutes(30)));
        when(devices.findByUserIdAndFocusedHandle(USER, "shared_one")).thenReturn(List.of(gone));

        assertThat(service.holderOf(USER, "shared_one")).isEmpty();
    }

    @Test
    void anAbsentHandleHasNoHolderAndIsNeverLookedUp() {
        assertThat(service.holderOf(USER, null)).isEmpty();
        assertThat(service.holderOf(USER, "  ")).isEmpty();
        verify(devices, never()).findByUserIdAndFocusedHandle(any(), anyString());
    }

    // --- TAKEOVER: the user is in control, not the timeout ---------------------------------------------------

    @Test
    void takeoverMakesEveryOtherDeviceYieldTheHandle() {
        Device loser = seen("dev-2", "win", "shared_one", Instant.now());
        when(devices.findByUserIdAndFocusedHandleAndDeviceIdNot(USER, "shared_one", "dev-1"))
                .thenReturn(List.of(loser));

        assertThat(service.takeOverHandle(USER, "dev-1", "shared_one")).containsExactly("dev-2");
        // The machine keeps running — it stops HOLDING. Only the claim is taken away.
        assertThat(loser.getFocusedHandle()).isNull();
        assertThat(loser.getFocusedHandleAt()).isNull();
        verify(devices).saveAll(List.of(loser));
    }

    @Test
    void aYieldedDeviceDoesNotWinTheHandleBackByRe_reporting() {
        // The self-healing half. Re-reporting the same handle stamps a NEW claim, so the machine that took
        // over — whose claim is older — keeps it.
        Device loser = seen("dev-2", "win", "shared_one", Instant.now());
        stamp(loser, Instant.now().minusSeconds(3600));
        when(devices.findByUserIdAndFocusedHandleAndDeviceIdNot(USER, "shared_one", "dev-1"))
                .thenReturn(List.of(loser));
        service.takeOverHandle(USER, "dev-1", "shared_one");

        loser.setFocusedHandle("shared_one");   // it re-reports, still focused there

        assertThat(loser.getFocusedHandleAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void takeoverIsANoOpWithoutAHandleOrADevice() {
        assertThat(service.takeOverHandle(USER, "dev-1", null)).isEmpty();
        assertThat(service.takeOverHandle(USER, "dev-1", "  ")).isEmpty();
        assertThat(service.takeOverHandle(USER, "  ", "shared_one")).isEmpty();
        verify(devices, never()).findByUserIdAndFocusedHandleAndDeviceIdNot(any(), anyString(), anyString());
    }

    private static Device seen(String deviceId, String platform, String handle, Instant lastSeen) {
        Device device = Device.register(USER, deviceId, platform);
        device.setFocusedHandle(handle);
        org.springframework.test.util.ReflectionTestUtils.setField(device, "lastSeenAt", lastSeen);
        return device;
    }

    private static void stamp(Device device, Instant claimedAt) {
        org.springframework.test.util.ReflectionTestUtils.setField(device, "focusedHandleAt", claimedAt);
    }
}
