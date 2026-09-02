package com.reelypops.rpenduser.security;

import com.reelypops.rpenduser.stop.StopAction;
import com.reelypops.rpenduser.stop.StopOrderEvent;
import com.reelypops.rpenduser.stop.StopOrderEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * rpenduser's stop history, read as {@link SecurityEvent}s for rpadminserver to mirror.
 *
 * <p>A projection over {@code stop_order_event}, which is append-only — so unlike almost everything else
 * this service reports, what this endpoint returns for a given moment never changes afterwards.
 *
 * <p><strong>OLDEST FIRST, from a watermark.</strong> This is a feed to be drained, not a screen to be
 * read: the caller keeps the newest {@code occurredAt} it has stored and asks for what came after it. A
 * full page therefore means "ask again straight away", and no amount of activity can push an event past a
 * reader that is behind. Newest-first would quietly drop the middle of any burst.
 */
@RestController
@RequestMapping("/enduser/v1/internal/security-events")
public class SecurityEventController {

    private static final String SERVICE = "rpenduser";

    /** A page big enough to be useful and small enough that nobody accidentally asks for the table. */
    private static final int MAX_PAGE = 500;

    private final StopOrderEventRepository history;

    public SecurityEventController(StopOrderEventRepository history) {
        this.history = history;
    }

    /**
     * @param until optional upper bound, for a reader RE-READING a window it has already passed. The drain
     *              only moves forward; repair has to go back, and has to terminate.
     */
    @GetMapping
    public List<SecurityEvent> events(@RequestParam(required = false) Instant since,
                                      @RequestParam(required = false) Instant until,
                                      @RequestParam(required = false, defaultValue = "200") int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        Instant from = since == null ? Instant.EPOCH : since;
        List<StopOrderEvent> rows = until == null
                ? history.findByOccurredAtGreaterThanOrderByOccurredAtAsc(from, PageRequest.of(0, size))
                : history.findByOccurredAtGreaterThanAndOccurredAtLessThanEqualOrderByOccurredAtAsc(
                        from, until, PageRequest.of(0, size));
        return rows.stream().map(SecurityEventController::project).toList();
    }

    /**
     * HOW MANY EVENTS THIS SERVICE HOLDS IN A WINDOW.
     *
     * <p>The mirror cannot detect its own gaps. It pages forward from a watermark derived from what it has
     * already stored, so a row landing below that watermark — a clock stepping backwards, two instances
     * whose clocks differ, a restore — is stepped over permanently while every read reports success. That
     * is a silent hole in the security record behind a screen saying every feed is reporting.
     *
     * <p>A count over a settled window is the cheapest question whose answer differs when rows are lost.
     *
     * @param since exclusive lower bound
     * @param until inclusive upper bound. Both required — an unbounded count answers a question nobody asked.
     */
    @GetMapping("/count")
    public Count count(@RequestParam Instant since, @RequestParam Instant until) {
        return new Count(history.countByOccurredAtGreaterThanAndOccurredAtLessThanEqual(since, until));
    }

    /** @param count how many events fall in the requested window */
    public record Count(long count) {
    }

    /**
     * A stop-order event as a security event.
     *
     * <p><strong>The type carries the ACTION, not just the fact.</strong> "A stop was issued" and "a KILL
     * was issued" are different sentences to an operator, and the alerting reads the type — so collapsing
     * them would make it impossible to treat a fleet-halting kill differently from a routine disable.
     */
    static SecurityEvent project(StopOrderEvent event) {
        return new SecurityEvent(
                event.getOccurredAt(),
                SERVICE,
                "STOP_ORDER_" + event.getEvent() + "_" + event.getAction(),
                severityOf(event),
                event.getUserId(),
                event.getClaimedBy(),
                // NEVER authenticated. This surface is a shared key with no per-caller identity, so
                // claimedBy is a string somebody typed — and the console must be able to show that.
                false,
                event.getSourceIp(),
                null,
                event.getAction() + " " + event.getEvent().name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * <p>ISSUING A KILL OR A REMOVE IS RED: it halts a paying customer's work, and it is rare. A DISABLE is
     * AMBER — routine enough for billing and cancellations that a red alert on it would fire during
     * ordinary admin work, which is how a red channel stops being read.
     *
     * <p>A SIGN_OUT stops nothing, and CLEARING is a recovery. Neither is an alarm; both are the record.
     */
    private static String severityOf(StopOrderEvent event) {
        if (event.getEvent() != StopOrderEvent.Event.ISSUED) {
            return "INFO";
        }
        if (event.getAction() == StopAction.KILL || event.getAction() == StopAction.REMOVE) {
            return "RED";
        }
        return event.getAction() == StopAction.DISABLE ? "AMBER" : "INFO";
    }
}
