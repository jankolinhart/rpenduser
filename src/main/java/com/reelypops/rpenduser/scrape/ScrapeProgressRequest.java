package com.reelypops.rpenduser.scrape;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * A machine reporting what it is doing about one group's deep scrape. Sent while a scrape runs (throttled — a
 * count that rises does not need to be sent on every post) and once more when it ends.
 *
 * <p>{@code scannedCount} is posts seen, not a percentage: a deep scrape walks until the tagged grid ends, so
 * there is no total to divide by and the console must show a count rather than a bar that pretends to know.</p>
 */
public record ScrapeProgressRequest(@NotBlank String deviceId, @NotBlank String igAccount,
                                    @NotBlank String state, Integer scannedCount,
                                    Instant startedAt, Instant finishedAt, String message) {
}
