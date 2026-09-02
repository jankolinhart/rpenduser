package com.reelypops.rpenduser.stop;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append and read. NOTHING ELSE.
 *
 * <p>Deliberately {@link Repository} rather than {@code JpaRepository}: the latter inherits the whole
 * {@code delete*} family, and a history with a delete method on it is one somebody will eventually call —
 * most likely from a well-meaning cleanup. What cannot be reached cannot be reached by accident.
 */
public interface StopOrderEventRepository extends Repository<StopOrderEvent, UUID> {

    StopOrderEvent save(StopOrderEvent event);

    List<StopOrderEvent> findByUserIdOrderByOccurredAtDesc(UUID userId);

    List<StopOrderEvent> findAllByOrderByOccurredAtDesc();

    /**
     * A page of history OLDEST FIRST, starting after a watermark.
     *
     * <p>Ascending, which looks backwards for a history and is the only ordering a mirror can follow safely.
     * Newest-first with a limit answers "the last N", so a reader that then advanced its watermark past them
     * would step over everything older in the same window and never come back for it — and the window only
     * overflows when a lot is happening at once, which is exactly the moment the record matters.
     */
    List<StopOrderEvent> findByOccurredAtGreaterThanOrderByOccurredAtAsc(Instant since, Pageable page);

    /**
     * A CLOSED WINDOW, for a reader that wants to REPAIR rather than to advance. The drain above only moves
     * forward from a watermark; a reader that has found a hole has to be able to go back, and the read has
     * to terminate.
     */
    List<StopOrderEvent> findByOccurredAtGreaterThanAndOccurredAtLessThanEqualOrderByOccurredAtAsc(
            Instant since, Instant until, Pageable page);

    /**
     * HOW MANY WE HOLD IN A WINDOW — the number a reader compares against its own to learn it is missing
     * something. A drain cannot detect its own gaps: it pages forward from a watermark derived from what it
     * already stored, so anything it stepped over is invisible to it for ever while every read reports
     * success.
     */
    long countByOccurredAtGreaterThanAndOccurredAtLessThanEqual(Instant since, Instant until);
}
