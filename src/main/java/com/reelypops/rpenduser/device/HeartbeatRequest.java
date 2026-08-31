package com.reelypops.rpenduser.device;

import jakarta.validation.constraints.NotBlank;

/**
 * M5.1 heartbeat body — the frequent, tiny check-in the client sends via the BFF: the device fingerprint, its
 * IG-session liveness ({@code online}), and the {@code stateHash} of its current backward-contract state. The
 * backend refreshes liveness and answers whether a fresh full report is needed (a {@code stateHash} mismatch).
 *
 * <p>M5.3c: also carries the client's {@code appVersion} (optional — older clients omit it) so the backend can flag
 * an outdated client in the reply.
 *
 * <p>It also carries {@code deviceName}, which is what makes the beat the RENAME path: the label rides the
 * call that already runs every 60s, so there is no rename endpoint and no second path to fall out of sync.
 * Optional like {@code appVersion}, and absent means "no opinion" rather than "clear it".
 */
public record HeartbeatRequest(@NotBlank String deviceId, boolean online, @NotBlank String stateHash,
                               String appVersion, String deviceName, String ackStopOrderId) {
}
