package com.reelypops.rpenduser.release;

import java.time.Instant;
import java.util.List;

/**
 * The admin-facing client-release status returned by the internal admin surface — the pending-release view (what is
 * published-and-downloadable vs what has been announced to clients) plus the effective gate state. rpadminserver /
 * rpadminfrontend render "a new release is available" + the "Publish Announcement Now" gate from this.
 *
 * @param publishedVersion    the latest verified-downloadable version (or {@code null} when nothing is published)
 * @param publishedAt         when that version became available
 * @param announcedVersion    the version currently announced to clients (or {@code null})
 * @param announcedAt         when it was announced
 * @param pendingAnnouncement true when a published version has not yet been announced (awaiting the admin gate)
 * @param gateEnabled         the effective human gate (always true on PROD)
 * @param urgency             the announced blurb's urgency (or {@code null})
 * @param highlights          the announced blurb bullets (empty when none)
 */
public record PendingReleaseView(
        String publishedVersion, Instant publishedAt,
        String announcedVersion, Instant announcedAt,
        boolean pendingAnnouncement, boolean gateEnabled,
        UpdateUrgency urgency, List<String> highlights) {

    /** The status when nothing has been published yet (no row) — gate on by default. */
    static PendingReleaseView empty() {
        return new PendingReleaseView(null, null, null, null, false, true, null, List.of());
    }
}
