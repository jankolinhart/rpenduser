package com.reelypops.rpenduser.device;

import java.time.Instant;

/**
 * Device view returned to the client — deliberately omits the internal row id and the user id (the client
 * already knows its own device fingerprint and identity).
 */
public record DeviceResponse(String deviceId, String platform, Instant firstSeenAt, Instant lastSeenAt) {

    static DeviceResponse of(Device device) {
        return new DeviceResponse(device.getDeviceId(), device.getPlatform(),
                device.getFirstSeenAt(), device.getLastSeenAt());
    }
}
