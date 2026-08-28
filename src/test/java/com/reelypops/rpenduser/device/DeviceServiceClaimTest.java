package com.reelypops.rpenduser.device;

import org.junit.jupiter.api.Test;

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
}
