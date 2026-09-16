package com.reelypops.rpenduser.standing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps each account's over-the-plan clocks, one per {@link PlanPool}.
 *
 * <h2>One door, and it is an OBSERVATION</h2>
 * {@link #observe} takes the pools a reader currently finds over and returns the marks that now stand. There
 * is deliberately no separate "start" and "stop": a caller that had to decide which to call would have to
 * know what was already recorded, and would then be free to get it wrong in the one direction that matters —
 * calling start again on a clock that is already running.
 *
 * <p>So the caller states the world and this class reconciles:</p>
 * <ul>
 *   <li>a pool that is over and has no mark gets one, stamped now;</li>
 *   <li>a pool that is over and already has one is <b>left exactly as it is</b>;</li>
 *   <li>a pool that is no longer over has its mark deleted, so coming back inside the plan genuinely clears
 *       the clock rather than pausing it.</li>
 * </ul>
 *
 * <p><strong>The second rule is the load-bearing one.</strong> Re-stamping a standing mark would hold every
 * account at day zero for ever, and the seventh day would never arrive — a failure that looks exactly like
 * the feature working, on every screen, for everybody.</p>
 *
 * <h2>Idempotent by construction</h2>
 * The BFF calls this on every plan-standing read, which happens whenever an app starts or a browser session
 * begins. Two of those arriving together must not produce two clocks for one pool; the unique constraint on
 * {@code (user_id, pool)} is what guarantees it, and this method reads-then-writes inside one transaction so
 * the loser of a race fails rather than inserting a duplicate.
 */
@Service
public class OverPlanMarkService {

    private final OverPlanMarkRepository marks;

    public OverPlanMarkService(OverPlanMarkRepository marks) {
        this.marks = marks;
    }

    /**
     * Reconcile this account's clocks against what a reader currently sees.
     *
     * @param userId the account
     * @param over   every pool observed to be over its ceiling right now — empty means everything is inside
     * @return the marks that stand afterwards, in {@link PlanPool} order so the answer is stable
     */
    @Transactional
    public List<OverPlanMark> observe(UUID userId, Set<PlanPool> over) {
        Set<PlanPool> stillOver = over.isEmpty() ? EnumSet.noneOf(PlanPool.class) : EnumSet.copyOf(over);
        List<OverPlanMark> existing = marks.findByUserId(userId);

        List<OverPlanMark> standing = new ArrayList<>();
        Set<PlanPool> alreadyMarked = EnumSet.noneOf(PlanPool.class);
        for (OverPlanMark mark : existing) {
            if (stillOver.contains(mark.getPool())) {
                // UNTOUCHED. The clock has been running since this row was written and must keep running.
                standing.add(mark);
                alreadyMarked.add(mark.getPool());
            } else {
                // Back inside the plan: the clock is CLEARED, not paused. Going over again later is a fresh
                // seven days, because it is a fresh decision somebody has to make.
                marks.delete(mark);
            }
        }

        // House style here is `Instant.now()` — this service has no branch that depends on WHAT the
        // time is, only on whether a stamp already exists, so a clock to move would buy nothing.
        Instant now = Instant.now();
        for (PlanPool pool : stillOver) {
            if (!alreadyMarked.contains(pool)) {
                standing.add(marks.save(new OverPlanMark(userId, pool, now)));
            }
        }

        standing.sort((a, b) -> a.getPool().compareTo(b.getPool()));
        return standing;
    }
}
