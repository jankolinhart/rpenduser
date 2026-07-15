package com.reelypops.rpenduser.device;

import jakarta.validation.constraints.NotBlank;

/** Device registration payload: the client's opaque device fingerprint + granular platform (no hostname). */
public record RegisterDeviceRequest(@NotBlank String deviceId, String platform) {
}
