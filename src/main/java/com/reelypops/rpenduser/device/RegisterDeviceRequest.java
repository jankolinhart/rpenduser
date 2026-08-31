package com.reelypops.rpenduser.device;

import jakarta.validation.constraints.NotBlank;

/**
 * Device registration payload: the client's opaque device fingerprint, its granular platform, and what the
 * user calls this machine.
 *
 * <p>{@code deviceName} is optional on the wire — a client that predates naming sends none, and an absent
 * name leaves any stored one alone rather than clearing it. It is NOT length-validated here: see
 * {@link Device#nameThisMachine(String)}, which truncates, because refusing a registration over the length of
 * a label would be a worse answer than a shortened one.
 */
public record RegisterDeviceRequest(@NotBlank String deviceId, String platform, String deviceName) {
}
