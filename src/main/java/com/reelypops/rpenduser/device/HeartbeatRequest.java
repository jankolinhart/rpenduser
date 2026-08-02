package com.reelypops.rpenduser.device;

import jakarta.validation.constraints.NotBlank;

/**
 * M5.1 heartbeat body — the frequent, tiny check-in the client sends via the BFF: the device fingerprint, its
 * IG-session liveness ({@code online}), and the {@code stateHash} of its current backward-contract state. The
 * backend refreshes liveness and answers whether a fresh full report is needed (a {@code stateHash} mismatch).
 */
public record HeartbeatRequest(@NotBlank String deviceId, boolean online, @NotBlank String stateHash) {
}
