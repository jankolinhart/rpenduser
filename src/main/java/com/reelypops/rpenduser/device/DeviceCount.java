package com.reelypops.rpenduser.device;

import java.util.UUID;

/**
 * Per-user device tally for the admin dashboard's device column (one entry per user with >= 1 device).
 *
 * @param count how many machines this user has ever registered
 * @param live  how many of them are LIVE right now — the half that answers "can this user be helped this
 *              minute?". Counted against the same band the drill-down shows, so the summary and the detail
 *              can never disagree.
 */
public record DeviceCount(UUID userId, long count, long live) {
}
