package com.reelypops.rpenduser.device;

/**
 * M5.1 heartbeat reply: whether the client should follow up with a full backward-contract report — {@code true}
 * when its current {@code stateHash} differs from the last report the backend stored (so the larger payload is
 * only sent when something actually changed).
 */
public record HeartbeatResponse(boolean reportNeeded) {
}
