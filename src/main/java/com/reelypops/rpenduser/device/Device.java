package com.reelypops.rpenduser.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A device registered to an end user (D3): one row per (user, machine). {@code deviceId} is the client's
 * opaque, salted hardware-fingerprint hash — never the raw MAC/UUID — and {@code platform} is granular OS
 * info (e.g. {@code macOS 14.5}); there is deliberately no hostname, which can embed a real name. rppayment
 * reads this registry for seat enforcement.
 */
@Entity
@Table(name = "device")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Device {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "platform")
    private String platform;

    @CreationTimestamp
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /**
     * The client's IG-session liveness at the last check-in (M5.1 heartbeat) — {@code true} online / {@code false}
     * offline, {@code null} until the first heartbeat. Feeds the P6 duty scheduler's "who is reachable now".
     */
    @Column(name = "online")
    private Boolean online;

    /**
     * The fingerprint of the last full backward-contract report we stored (M5.1). A heartbeat carries the client's
     * CURRENT {@code stateHash}; when it differs from this stored one the backend asks for a fresh report. Null
     * until the first report lands.
     */
    @Column(name = "state_hash")
    private String stateHash;

    /** The last full backward-contract report snapshot (M5.1) verbatim as JSON — stored opaquely (ignore-unknown, additive). */
    @Column(name = "report")
    private String report;

    /** When the last report was stored (M5.1); null until the first report. */
    @Column(name = "last_report_at")
    private Instant lastReportAt;

    /**
     * The Instagram handle this device last reported as IN FOCUS, normalised lower-case — projected out of
     * {@link #report} so it can be queried. {@code null} when the device has never reported, or reports
     * nothing in focus.
     *
     * <p>The report stays the source of truth; this is a derived index onto one field of it. It exists
     * because two devices focusing the SAME account do the same work twice at twice the like-rate, which is
     * an Instagram-detection risk rather than a quota question — so it must be answerable by a QUERY, not by
     * parsing every stored blob.
     */
    @Column(name = "focused_handle")
    private String focusedHandle;

    /**
     * When this device STARTED claiming {@link #focusedHandle} — the ordering that decides who HOLDS a
     * contested handle. Stamped only when the handle CHANGES; a report that repeats the same handle leaves it
     * alone, or "earliest claim" would mean nothing and the incumbent would lose its own handle every 60s.
     */
    @Column(name = "focused_handle_at")
    private Instant focusedHandleAt;

    /**
     * When this client last reported a CLEAN shutdown, or {@code null}.
     *
     * <p>A HINT, never the truth. A crash, a power cut or a dead network sends no goodbye, so presence is
     * still derived from {@link #lastSeenAt}; this only brings the ordinary case forward from "within five
     * minutes" to "immediately". Treating it as authoritative would let the console show a crashed machine
     * as running for ever — the failure it exists to remove.
     *
     * <p>Cleared by every check-in, so a device that comes back is simply back.
     */
    @Column(name = "shutdown_at")
    private Instant shutdownAt;

    private Device(UUID userId, String deviceId, String platform) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.deviceId = deviceId;
        this.platform = platform;
        this.lastSeenAt = Instant.now();
    }

    /** Register a newly-seen device for a user. */
    public static Device register(UUID userId, String deviceId, String platform) {
        return new Device(userId, deviceId, platform);
    }

    /** Record that an already-registered device checked in again — refresh its platform + last-seen. */
    public void heartbeat(String platform) {
        this.platform = platform;
        this.lastSeenAt = Instant.now();
    }

    /** Record a lightweight M5.1 heartbeat: refresh liveness (online + last-seen). */
    public void checkIn(boolean online) {
        this.online = online;
        this.lastSeenAt = Instant.now();
        // Anything heard from is not shut down, whatever it said last time. Clearing here rather than at the
        // call sites is what keeps "came back" from needing to be handled by every caller separately.
        this.shutdownAt = null;
    }

    /** The client said it is closing cleanly. */
    public void sayGoodbye() {
        Instant now = Instant.now();
        this.shutdownAt = now;
        // Last-seen moves too: this IS the last we heard from it, and leaving it stale would make the age
        // shown next to the badge older than the event that produced the badge.
        this.lastSeenAt = now;
    }

    /** Store a full M5.1 backward-contract report: its snapshot + fingerprint, refreshing last-report + last-seen. */
    /**
     * The handle this device claims, or {@code null}. Absent is ABSENT: a device that has never reported, or
     * reports none in focus, claims nothing — it must never read as claiming "" or as conflicting with
     * another silent device.
     */
    public String getFocusedHandle() {
        return focusedHandle;
    }

    public Instant getFocusedHandleAt() {
        return focusedHandleAt;
    }

    public Instant getShutdownAt() {
        return shutdownAt;
    }

    /**
     * Normalised at the setter so every comparison downstream is against one spelling.
     *
     * <p>The claim stamp moves ONLY when the handle actually changes. A device re-reporting the same handle
     * keeps its original stamp and therefore keeps its seniority — which is the whole point, because the
     * operator's rule is that the incumbent holds. Re-stamping on every report would let a device lose its
     * own handle to a challenger simply by staying alive.
     */
    public void setFocusedHandle(String handle) {
        String normalised = handle == null || handle.isBlank()
                ? null
                : handle.trim().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Objects.equals(this.focusedHandle, normalised)) {
            this.focusedHandle = normalised;
            this.focusedHandleAt = normalised == null ? null : Instant.now();
        }
    }

    /**
     * Give up this handle, so a challenger can take it. Used by the deliberate takeover: the loser keeps
     * running, it simply stops HOLDING. When it next reports the same handle it is stamped afresh and is now
     * the junior claim, so it correctly does not win the handle back.
     */
    public void releaseFocusedHandle() {
        this.focusedHandle = null;
        this.focusedHandleAt = null;
    }

    public void applyReport(String report, String stateHash) {
        this.report = report;
        this.stateHash = stateHash;
        this.lastReportAt = Instant.now();
        this.lastSeenAt = Instant.now();
    }
}
