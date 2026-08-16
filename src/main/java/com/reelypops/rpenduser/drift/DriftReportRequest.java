package com.reelypops.rpenduser.drift;

import java.util.UUID;

/**
 * The outbound drift-ingest body forwarded to rpsupportgroup (M5 re-vet consumer). {@code kind} is the rpsupportgroup
 * {@code DriftKind} NAME; the optional tally / nomination / measurement fields are omitted on the wire (app-wide
 * non-null Jackson). Mirrors rpsupportgroup's {@code DriftReport}.
 *
 * <p>{@code evidenceImage} is the picture a drifted marker was ACTUALLY posted with, captured by the desktop client
 * and passed straight through — Jackson carries a {@code byte[]} as base64. rpenduser never inspects it and never
 * fetches it: directive B1 means no cloud service contacts Instagram, so this relay is the only route by which an
 * administrator can see what a marker owner is posting today.</p>
 */
public record DriftReportRequest(
        String kind,
        String reporterDeviceId,
        UUID reporterUserId,
        String nominatedOwnerHandle,
        Integer agreePass,
        Integer disagreePass,
        Integer persistenceCount,
        String markerRole,
        String markerText,
        String detail,
        Integer imageDistance,
        Integer imageThreshold,
        String evidencePostId,
        byte[] evidenceImage) {

    /** The two original kinds, which carry no measurement and no picture. */
    public DriftReportRequest(String kind, String reporterDeviceId, UUID reporterUserId, String nominatedOwnerHandle,
                              Integer agreePass, Integer disagreePass, Integer persistenceCount) {
        this(kind, reporterDeviceId, reporterUserId, nominatedOwnerHandle, agreePass, disagreePass, persistenceCount,
                null, null, null, null, null, null, null);
    }
}
