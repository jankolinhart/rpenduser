package com.reelypops.rpenduser.release;

/** The admin gate-toggle body (DEV/TEST only; PROD forces the gate on regardless of {@code enabled}). */
public record GateRequest(boolean enabled) {
}
