package com.reelypops.rpenduser.scrape;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * What one machine is doing about one support group's deep scrape.
 *
 * <p>Current state, never history: the next scrape of that group on that machine overwrites this row. A
 * finished scrape keeps its DONE row on purpose — "412 posts, finished four minutes ago" is what an operator
 * reads after pressing start — and the corpus snapshots, not this, are the record of what was collected.</p>
 *
 * <p><strong>There is no IDLE.</strong> Absence is not idleness: a machine that has never scraped this group,
 * one on a build too old to report, and one whose report was lost all produce the same missing row. Only the
 * machine can say it is idle, and it says so by reporting nothing at all.</p>
 */
@Entity
@Table(name = "device_scrape_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceScrapeProgress {

    @Id
    private UUID id;

    /**
     * The device ROW, not the fingerprint string: the same machine can be registered to two customers, and a
     * scrape belongs to whichever of them asked for it.
     */
    @Column(name = "device_uuid", nullable = false)
    private UUID deviceUuid;

    @Column(name = "ig_account", nullable = false)
    private String igAccount;

    @Column(name = "state", nullable = false)
    private String state;

    /**
     * Posts seen so far. There is no total to divide it by — a deep scrape walks until the grid ends — so this
     * is a count that rises, never a percentage.
     */
    @Column(name = "scanned_count")
    private Integer scannedCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Why, in words an operator can act on, when the state is FAILED or REFUSED. */
    @Column(name = "message")
    private String message;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    DeviceScrapeProgress(UUID deviceUuid, String igAccount) {
        this.id = UUID.randomUUID();
        this.deviceUuid = deviceUuid;
        this.igAccount = igAccount;
    }

    void record(String state, Integer scannedCount, Instant startedAt, Instant finishedAt, String message,
                Instant now) {
        this.state = state;
        this.scannedCount = scannedCount;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.message = message;
        this.updatedAt = now;
    }
}
