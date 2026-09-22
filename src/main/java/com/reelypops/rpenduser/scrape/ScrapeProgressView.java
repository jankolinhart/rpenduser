package com.reelypops.rpenduser.scrape;

import java.time.Instant;

/**
 * One machine's deep-scrape state for one group, as the admin console reads it. {@code deviceId} is the
 * machine fingerprint the console already lists, so a row here lines up with a row in the picker.
 */
public record ScrapeProgressView(String deviceId, String igAccount, String state, Integer scannedCount,
                                 Instant startedAt, Instant finishedAt, String message, Instant updatedAt) {
}
