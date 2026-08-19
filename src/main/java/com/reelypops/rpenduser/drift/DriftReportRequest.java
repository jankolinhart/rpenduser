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
        byte[] evidenceImage,
        /**
         * THE CLIENT'S OWN fingerprint of {@code evidenceImage} — forwarded verbatim, never recomputed.
         *
         * <p>Measured 16/08/2026: the cloud's Java dHash lands 15–34 bits from the client's for identical bytes,
         * which is as far apart as unrelated images. A hash the client will not match is worse than none — it
         * looks healthy and fails silently. Cross-PLATFORM agreement is proven (0 bits on 3 OSes); it is crossing
         * IMPLEMENTATIONS that breaks.</p>
         */
        String evidenceImageHash,
        /**
         * WHICH weekday slot (JS 0=Sun … 6=Sat) the drifting reference belongs to — resolved by the CLIENT from
         * the marker's own postedOn through the group's per-weekday schedule, {@code null} for a flat/legacy
         * group. Forwarded verbatim like the hash: this relay must not derive, default or "fix" it, because a
         * wrong day silently writes a banner into the wrong reference — the 18/08/2026 fault this field exists to
         * end.
         */
        Integer markerWeekday) {

    /** The two original kinds, which carry no measurement and no picture. */
    public DriftReportRequest(String kind, String reporterDeviceId, UUID reporterUserId, String nominatedOwnerHandle,
                              Integer agreePass, Integer disagreePass, Integer persistenceCount) {
        this(kind, reporterDeviceId, reporterUserId, nominatedOwnerHandle, agreePass, disagreePass, persistenceCount,
                null, null, null, null, null, null, null, null, null);
    }
}
