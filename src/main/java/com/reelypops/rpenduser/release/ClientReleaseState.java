package com.reelypops.rpenduser.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The per-stage client-release state — a SINGLETON row (one rpenduser instance serves exactly one stage/channel).
 *
 * <p>{@code publishedVersion} is the pipeline-verified "downloadable" fact (ingested from the release pointer);
 * {@code announcedVersion} + the curated blurb ({@code announcementUrgency} + newline-joined
 * {@code announcementHighlights} — never an internal changelog) is what the admin GATE promotes and what clients are
 * compared against. Announced always equals a version that was published, so a client is never offered a version that
 * is not verified-downloadable. The human gate ({@code gateEnabled}) is admin-toggleable on DEV/TEST and forced on for
 * PROD (enforced in {@link ClientReleaseService}). See {@code client-update-announcement-design.md}.</p>
 */
@Entity
@Table(name = "client_release_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientReleaseState {

    /** The one and only row id — there is exactly one client-release state per rpenduser instance. */
    static final Integer SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(name = "published_version")
    private String publishedVersion;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "announced_version")
    private String announcedVersion;

    @Column(name = "announced_at")
    private Instant announcedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "announcement_urgency", length = 16)
    private UpdateUrgency announcementUrgency;

    /** The curated public blurb bullets, newline-joined (never internal/technical detail). */
    @Column(name = "announcement_highlights")
    private String announcementHighlights;

    /** The human gate: true = an admin must announce; false = auto-announce on publish (DEV/TEST only). PROD forces true. */
    @Column(name = "gate_enabled", nullable = false)
    private boolean gateEnabled = true;

    /** A fresh, unsaved singleton (nothing published or announced, gate on). */
    static ClientReleaseState initial() {
        ClientReleaseState s = new ClientReleaseState();
        s.id = SINGLETON_ID;
        return s;
    }

    /** Record a newly-published, verified-downloadable version. */
    void publish(String version, Instant at) {
        this.publishedVersion = version;
        this.publishedAt = at;
    }

    /** Promote a version to announced with the curated blurb (the admin gate action, or auto on gate-off publish). */
    void announce(String version, UpdateUrgency urgency, String highlights, Instant at) {
        this.announcedVersion = version;
        this.announcementUrgency = urgency;
        this.announcementHighlights = highlights;
        this.announcedAt = at;
    }

    /** Set the human gate on/off (the effective PROD-forced value is decided by the service). */
    void gate(boolean enabled) {
        this.gateEnabled = enabled;
    }
}
