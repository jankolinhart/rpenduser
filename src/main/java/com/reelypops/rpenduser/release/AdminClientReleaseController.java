package com.reelypops.rpenduser.release;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal admin surface for the M5.3c client-release announcement gate, on {@code /enduser/v1/internal} and gated by
 * {@code X-Internal-Api-Key} — consumed east-west by the rpadminserver BFF for the admin console. Exposes the
 * pending-release status, the published-version push (rpadminserver's S3 read), the "Publish Announcement Now" gate
 * action, and the DEV/TEST gate toggle.
 */
@RestController
@RequestMapping("/enduser/v1/internal/client-release")
public class AdminClientReleaseController {

    private final ClientReleaseService releases;

    public AdminClientReleaseController(ClientReleaseService releases) {
        this.releases = releases;
    }

    /** The pending-release status + gate state for the admin console. */
    @GetMapping
    public PendingReleaseView status() {
        return releases.pending();
    }

    /**
     * Internal push of a newly-discovered, verified-downloadable version — rpadminserver reads the S3 artifact bucket
     * (the newest COMPLETE version per channel) and pushes it here (rpenduser stays AWS-free). When the gate is off
     * (DEV/TEST) it auto-announces; otherwise it becomes a pending release awaiting the admin gate.
     */
    @PostMapping("/published")
    public PendingReleaseView published(@RequestBody PublishedRequest req) {
        releases.updatePublishedVersion(req.version());
        return releases.pending();
    }

    /**
     * The admin GATE action — announce the current published version with the curated blurb, so clients on this stage
     * start being told to update. 409 when there is nothing published to announce.
     */
    @PostMapping("/announce")
    public PendingReleaseView announce(@RequestBody AnnounceRequest req) {
        return releases.announce(req.highlights(), req.urgency())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no published version to announce"));
    }

    /** Toggle the human gate (DEV/TEST only); PROD forces it on regardless. */
    @PutMapping("/gate")
    public PendingReleaseView gate(@RequestBody GateRequest req) {
        return releases.setGateEnabled(req.enabled());
    }
}
