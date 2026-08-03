package com.reelypops.rpenduser.drift;

import java.util.UUID;

/**
 * The outbound drift-ingest body forwarded to rpsupportgroup (M5 re-vet consumer). {@code kind} is the rpsupportgroup
 * {@code DriftKind} NAME ({@code MARKER_DISAGREE} / {@code NEW_OWNER}); the optional tally / nomination fields are
 * omitted on the wire (app-wide non-null Jackson). Mirrors rpsupportgroup's {@code DriftReport}.
 */
public record DriftReportRequest(
        String kind,
        String reporterDeviceId,
        UUID reporterUserId,
        String nominatedOwnerHandle,
        Integer agreePass,
        Integer disagreePass,
        Integer persistenceCount) {
}
