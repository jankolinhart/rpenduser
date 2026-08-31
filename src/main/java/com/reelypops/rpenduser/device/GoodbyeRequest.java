package com.reelypops.rpenduser.device;

import jakarta.validation.constraints.NotBlank;

/**
 * "I am closing cleanly." The whole body — the user is the JWT subject upstream, never the payload.
 */
public record GoodbyeRequest(@NotBlank String deviceId) {
}
