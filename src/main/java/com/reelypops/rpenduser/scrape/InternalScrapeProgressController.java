package com.reelypops.rpenduser.scrape;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal deep-scrape progress surface (key-authed, no end-user JWT), both halves of one fact:
 *
 * <ul>
 *   <li><b>the machine writes</b> through the rpserver BFF, which has already validated the customer's token
 *       and passes the trusted {@code sub} in the path — so a client can only ever report about its own
 *       machine, and only about a machine registered to it;</li>
 *   <li><b>the admin console reads</b> a whole group at once, because "who is scraping @thisgroup" is a
 *       question no single machine can answer about itself.</li>
 * </ul>
 */
@RestController
@RequestMapping("/enduser/v1/internal")
public class InternalScrapeProgressController {

    private final ScrapeProgressService progress;

    public InternalScrapeProgressController(ScrapeProgressService progress) {
        this.progress = progress;
    }

    /** A machine reporting its own deep-scrape state. 204: the report is stored, there is nothing to say back. */
    @PostMapping("/users/{userId}/devices/scrape-progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@PathVariable UUID userId, @Valid @RequestBody ScrapeProgressRequest req) {
        progress.report(userId, req);
    }

    /** Every machine's state for one group — the console's deep-scrape table. */
    @GetMapping("/groups/{igAccount}/scrape-progress")
    public List<ScrapeProgressView> forGroup(@PathVariable String igAccount) {
        return progress.forGroup(igAccount);
    }

    /**
     * An unregistered machine or an unknown state is the caller's mistake, not ours. 400 rather than 404: the
     * client is not asking for a resource, it is making a claim about itself that does not hold.
     */
    @ExceptionHandler(ScrapeProgressService.UnknownScrapeReport.class)
    public ResponseEntity<Map<String, String>> onUnknownReport(ScrapeProgressService.UnknownScrapeReport e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
