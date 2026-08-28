package com.reelypops.rpenduser.device;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE PROJECTION IS TOTAL, and that is the whole design.
 *
 * <p>A device report is stored opaquely and additively — ignore-unknown, future clients may send shapes this
 * version has never seen. So reading one field out of it must survive anything: a malformed body, a different
 * shape, a missing array. Anything unreadable means the device claims NOTHING.
 *
 * <p>Never an exception, because a projection failure must not fail the report that carries it. Never a blank,
 * because a blank handle would collide with every other silent device and manufacture conflicts out of
 * nothing — absence has to stay absence.
 */
class FocusedHandleProjectionTest {

    @Test
    void takesTheHandleMarkedInFocus() {
        String report = """
                {"deviceId":"d1","stateHash":"h",
                 "igAccounts":[{"handle":"quiet_one","inFocus":false,"sessionValid":true},
                               {"handle":"working_one","inFocus":true,"sessionValid":true}]}""";

        assertThat(DeviceService.focusedHandleOf(report)).isEqualTo("working_one");
    }

    @Test
    void claimsNothingWhenNoAccountIsInFocus() {
        String report = """
                {"igAccounts":[{"handle":"a","inFocus":false},{"handle":"b","inFocus":false}]}""";

        assertThat(DeviceService.focusedHandleOf(report)).isNull();
    }

    @Test
    void claimsNothingForAnAbsentOrEmptyAccountList() {
        assertThat(DeviceService.focusedHandleOf("{\"deviceId\":\"d1\"}")).isNull();
        assertThat(DeviceService.focusedHandleOf("{\"igAccounts\":[]}")).isNull();
    }

    @Test
    void claimsNothingRatherThanThrowingOnAnythingUnreadable() {
        // Each of these is a body a future or broken client could send. None may throw: the report is already
        // stored by the time this runs, and a parse fault must not turn a stored snapshot into a 500.
        assertThat(DeviceService.focusedHandleOf(null)).isNull();
        assertThat(DeviceService.focusedHandleOf("")).isNull();
        assertThat(DeviceService.focusedHandleOf("   ")).isNull();
        assertThat(DeviceService.focusedHandleOf("not json at all")).isNull();
        assertThat(DeviceService.focusedHandleOf("{\"igAccounts\":\"a string, not an array\"}")).isNull();
        assertThat(DeviceService.focusedHandleOf("{\"igAccounts\":[{\"inFocus\":true}]}")).isNull();
        assertThat(DeviceService.focusedHandleOf("{\"igAccounts\":[{\"handle\":\"\",\"inFocus\":true}]}")).isNull();
    }

    @Test
    void aBlankHandleIsAbsent_neverAnEmptyClaim() {
        // The sharp one. An empty-string claim would MATCH every other device that also stored "", inventing a
        // conflict between two devices that are each working nothing.
        assertThat(DeviceService.focusedHandleOf("{\"igAccounts\":[{\"handle\":\"   \",\"inFocus\":true}]}"))
                .isNull();
    }

    @Test
    void theStoredValueIsNormalisedSoEveryComparisonIsAgainstOneSpelling() {
        Device device = Device.register(java.util.UUID.randomUUID(), "d1", "mac");

        device.setFocusedHandle("  Working_One  ");
        assertThat(device.getFocusedHandle()).isEqualTo("working_one");

        device.setFocusedHandle("   ");
        assertThat(device.getFocusedHandle()).isNull();
    }
}
