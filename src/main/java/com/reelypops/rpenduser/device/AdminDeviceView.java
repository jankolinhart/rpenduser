package com.reelypops.rpenduser.device;

import java.time.Instant;

/**
 * Full device view for the admin console's internal surface — every field the registry holds about a machine:
 * the opaque hardware-fingerprint hash ({@code deviceId}), the granular platform string, and the first/last
 * seen timestamps. There is deliberately no hostname or raw hardware id (D3).
 */
public record AdminDeviceView(String deviceId, String platform, Instant firstSeenAt, Instant lastSeenAt) {

    static AdminDeviceView of(Device device) {
        return new AdminDeviceView(device.getDeviceId(), device.getPlatform(),
                device.getFirstSeenAt(), device.getLastSeenAt());
    }
}
