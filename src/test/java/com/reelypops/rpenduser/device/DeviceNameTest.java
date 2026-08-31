package com.reelypops.rpenduser.device;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A MACHINE HAS A NAME, AND ITS OWNER IS THE MACHINE.
 *
 * <p>{@code deviceId} is an opaque SHA-256 and {@code platform} is "Mac OS X 14.5". Neither can answer "which
 * of my laptops is that?", and every surface that has to ask — the client's takeover modal, rpauth's seat
 * refusal, the admin console's seat list — is unusable for a user with two machines on one OS.
 *
 * <p>The name is chosen ON the machine and rides registration and every heartbeat, so a rename is simply the
 * next check-in. Two rules make that work, and both are asserted here rather than assumed:
 *
 * <ul>
 *   <li><b>A present name replaces</b>, because the machine is the authority on what it is called.</li>
 *   <li><b>An absent name changes nothing.</b> A client with a name always has one to send — it falls back to
 *       its own OS label rather than a blank — so silence can only mean a caller that predates naming, and
 *       forgetting a good name on its say-so would be strictly worse than keeping it.</li>
 * </ul>
 */
class DeviceNameTest {

    private static final UUID USER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceService service = new DeviceService(devices);

    private Device saved() {
        when(devices.save(any(Device.class))).thenAnswer(call -> call.getArgument(0));
        return null;
    }

    private void existing(Device device) {
        when(devices.findByUserIdAndDeviceId(USER, device.getDeviceId())).thenReturn(Optional.of(device));
    }

    @Test
    void registrationStoresWhatTheUserCallsThisMachine() {
        saved();
        when(devices.findByUserIdAndDeviceId(USER, "dev-1")).thenReturn(Optional.empty());

        Device device = service.register(USER, "dev-1", "Mac OS X 14.5", "Kitchen iMac");

        assertThat(device.getDeviceName()).isEqualTo("Kitchen iMac");
    }

    @Test
    void aReRegistrationRENAMES_becauseTheMachineIsTheAuthorityOnItsOwnName() {
        saved();
        Device device = Device.register(USER, "dev-1", "Mac OS X 14.5");
        device.nameThisMachine("Old name");
        existing(device);

        service.register(USER, "dev-1", "Mac OS X 14.5", "New name");

        assertThat(device.getDeviceName()).isEqualTo("New name");
    }

    @Test
    void theHEARTBEATcarriesARename_whichIsWhyThereIsNoRenameEndpoint() {
        saved();
        Device device = Device.register(USER, "dev-1", "Mac OS X 14.5");
        device.nameThisMachine("Old name");
        existing(device);

        service.heartbeat(USER, "dev-1", true, "hash", "Studio Mac", "1.2.3");

        assertThat(device.getDeviceName()).isEqualTo("Studio Mac");
    }

    @Test
    void aHeartbeatWithNoNameLEAVESTheStoredOne_absentIsNoOpinionNotAClear() {
        // The failure this prevents: a caller that knows nothing about names quietly wiping a good one on
        // every 60s beat, so the console loses the label between one minute and the next.
        saved();
        Device device = Device.register(USER, "dev-1", "Mac OS X 14.5");
        device.nameThisMachine("Kitchen iMac");
        existing(device);

        service.heartbeat(USER, "dev-1", true, "hash", null, "1.2.3");

        assertThat(device.getDeviceName()).isEqualTo("Kitchen iMac");
    }

    @Test
    void aBlankNameIsAlsoNoOpinion_becauseWhitespaceIsNotAName() {
        saved();
        Device device = Device.register(USER, "dev-1", "Mac OS X 14.5");
        device.nameThisMachine("Kitchen iMac");
        existing(device);

        service.register(USER, "dev-1", "Mac OS X 14.5", "   ");

        assertThat(device.getDeviceName()).isEqualTo("Kitchen iMac");
    }

    @Test
    void anOverlongNameIsTRUNCATEDratherThanRefused() {
        // It arrives on the heartbeat, which is also carrying liveness and the report-needed decision.
        // Failing that call over the length of a LABEL would trade presence for tidiness.
        Device device = Device.register(USER, "dev-1", "Mac OS X 14.5");

        device.nameThisMachine("x".repeat(200));

        assertThat(device.getDeviceName()).hasSize(Device.MAX_NAME_LENGTH);
    }

    @Test
    void aNameIsTrimmed_soTheConsoleNeverRendersLeadingSpace() {
        Device device = Device.register(USER, "dev-1", "Mac OS X 14.5");

        device.nameThisMachine("  Kitchen iMac  ");

        assertThat(device.getDeviceName()).isEqualTo("Kitchen iMac");
    }

    @Test
    void aMachineThatHasNeverBeenNamedHasNoName_andCallersFallBackToPlatform() {
        assertThat(Device.register(USER, "dev-1", "Mac OS X 14.5").getDeviceName()).isNull();
    }

    @Test
    void theNameReachesTheClientView() {
        Device device = Device.register(USER, "dev-1", "Mac OS X 14.5");
        device.nameThisMachine("Kitchen iMac");

        assertThat(DeviceResponse.of(device).deviceName()).isEqualTo("Kitchen iMac");
    }

    @Test
    void theNameReachesTheAdminView_besideThePlatformRatherThanInsteadOfIt() {
        // Both, deliberately: the name says WHICH machine, the platform still says what it runs.
        Device device = Device.register(USER, "dev-1", "Mac OS X 14.5");
        device.nameThisMachine("Kitchen iMac");

        AdminDeviceView view = AdminDeviceView.of(device, DeviceService.Presence.LIVE);

        assertThat(view.deviceName()).isEqualTo("Kitchen iMac");
        assertThat(view.platform()).isEqualTo("Mac OS X 14.5");
    }

    @Test
    void theHandleHolderIsNAMED_whichIsTheWholePointOnAUserWithTwoMacs() {
        // "Your other Mac has this account" is not an answer to someone with two Macs.
        Device device = Device.register(USER, "dev-2", "Mac OS X 14.5");
        device.nameThisMachine("Kitchen iMac");

        assertThat(InternalDeviceController.HandleHolder.of(device).deviceName()).isEqualTo("Kitchen iMac");
    }
}
