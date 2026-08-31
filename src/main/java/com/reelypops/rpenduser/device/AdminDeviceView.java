package com.reelypops.rpenduser.device;

import java.time.Instant;

/**
 * Full device view for the admin console's internal surface — every field the registry holds about a machine:
 * the opaque hardware-fingerprint hash ({@code deviceId}), what the user calls it ({@code deviceName}), the
 * granular platform string, and the first/last seen timestamps. There is still no raw hardware id (D3), and
 * the name is the user's own answer rather than anything read off the machine.
 *
 * <p>{@code deviceName} is nullable and the console must fall back to {@code platform} — an old row, or a
 * client that predates naming, has no name and must still be identifiable as something.
 */
public record AdminDeviceView(String deviceId, String platform, String deviceName,
                              Instant firstSeenAt, Instant lastSeenAt) {

    static AdminDeviceView of(Device device) {
        return new AdminDeviceView(device.getDeviceId(), device.getPlatform(), device.getDeviceName(),
                device.getFirstSeenAt(), device.getLastSeenAt());
    }
}
