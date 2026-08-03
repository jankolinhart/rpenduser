package com.reelypops.rpenduser.release;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Owns the per-stage client-release state (the M5.3c producer + admin human gate).
 *
 * <p>{@code publishedVersion} is the pipeline-verified "downloadable" fact; the admin GATE promotes it to
 * {@code announcedVersion} (+ a curated blurb), and only an announced version is ever offered to clients — announced
 * always equals the current published version at announce time, so a client is never told to update to a version that
 * is not verified-downloadable. On DEV/TEST the gate may be switched off (auto-announce on publish); PROD forces the
 * gate on. See {@code client-update-announcement-design.md}.</p>
 */
@Service
public class ClientReleaseService {

    private final ClientReleaseStateRepository repo;
    private final boolean prod;

    public ClientReleaseService(ClientReleaseStateRepository repo, @Value("${rp.stage:prod}") String stage) {
        this.repo = repo;
        this.prod = "prod".equalsIgnoreCase(stage == null ? "" : stage.trim());
    }

    /**
     * Ingest a newly-published, verified-downloadable version (from the pipeline pointer). No-op when blank or
     * unchanged. When the gate is OFF (DEV/TEST) the version is auto-announced with a default (empty) blurb; when the
     * gate is ON it only becomes a PENDING release awaiting the admin's "Publish Announcement Now".
     */
    @Transactional
    public void updatePublishedVersion(String version) {
        if (version == null || version.isBlank()) {
            return;
        }
        String v = version.trim();
        ClientReleaseState s = state();
        if (v.equals(s.getPublishedVersion())) {
            return;
        }
        s.publish(v, Instant.now());
        if (!gateOn(s)) {
            s.announce(v, UpdateUrgency.NORMAL, "", Instant.now());
        }
        repo.save(s);
    }

    /**
     * The admin GATE action: promote the current published version to announced with the curated blurb. Returns empty
     * when there is nothing published to announce (the caller maps that to a 409). Announced is set to exactly the
     * current published version — the {@code announced <= published} invariant.
     */
    @Transactional
    public Optional<PendingReleaseView> announce(List<String> highlights, UpdateUrgency urgency) {
        ClientReleaseState s = state();
        if (s.getPublishedVersion() == null || s.getPublishedVersion().isBlank()) {
            return Optional.empty();
        }
        s.announce(s.getPublishedVersion(), urgency == null ? UpdateUrgency.NORMAL : urgency,
                encodeHighlights(highlights), Instant.now());
        return Optional.of(view(repo.save(s)));
    }

    /** Toggle the human gate (DEV/TEST); PROD forces it on regardless of the requested value. */
    @Transactional
    public PendingReleaseView setGateEnabled(boolean enabled) {
        ClientReleaseState s = state();
        s.gate(prod || enabled);
        return view(repo.save(s));
    }

    /** The admin-facing status (pending release + gate state). */
    @Transactional(readOnly = true)
    public PendingReleaseView pending() {
        return repo.findById(ClientReleaseState.SINGLETON_ID).map(this::view).orElseGet(PendingReleaseView::empty);
    }

    /** The version clients are compared against (the announced one), or {@code null} when nothing is announced. */
    @Transactional(readOnly = true)
    public String announcedVersion() {
        return repo.findById(ClientReleaseState.SINGLETON_ID).map(ClientReleaseState::getAnnouncedVersion).orElse(null);
    }

    /** The public announcement payload for the announced version, or {@code null} when nothing is announced. */
    @Transactional(readOnly = true)
    public ReleaseAnnouncement announcement() {
        return repo.findById(ClientReleaseState.SINGLETON_ID)
                .filter(s -> s.getAnnouncedVersion() != null && !s.getAnnouncedVersion().isBlank())
                .map(s -> new ReleaseAnnouncement(s.getAnnouncedVersion(),
                        s.getAnnouncementUrgency() == null ? UpdateUrgency.NORMAL : s.getAnnouncementUrgency(),
                        decodeHighlights(s.getAnnouncementHighlights())))
                .orElse(null);
    }

    private ClientReleaseState state() {
        return repo.findById(ClientReleaseState.SINGLETON_ID).orElseGet(ClientReleaseState::initial);
    }

    private boolean gateOn(ClientReleaseState s) {
        return prod || s.isGateEnabled();
    }

    private PendingReleaseView view(ClientReleaseState s) {
        boolean pending = s.getPublishedVersion() != null && !s.getPublishedVersion().isBlank()
                && !s.getPublishedVersion().equals(s.getAnnouncedVersion());
        return new PendingReleaseView(s.getPublishedVersion(), s.getPublishedAt(),
                s.getAnnouncedVersion(), s.getAnnouncedAt(),
                pending, gateOn(s), s.getAnnouncementUrgency(), decodeHighlights(s.getAnnouncementHighlights()));
    }

    /** Newline-join the non-blank, whitespace-collapsed bullets (never any internal changelog). */
    private static String encodeHighlights(List<String> highlights) {
        if (highlights == null) {
            return "";
        }
        return highlights.stream()
                .filter(h -> h != null && !h.isBlank())
                .map(h -> h.trim().replaceAll("\\s+", " "))
                .collect(Collectors.joining("\n"));
    }

    private static List<String> decodeHighlights(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split("\n")).filter(line -> !line.isBlank()).toList();
    }
}
