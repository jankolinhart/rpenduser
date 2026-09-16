package com.reelypops.rpenduser.standing;

import java.time.Instant;

/** One standing clock: which ceiling this account is over, and since when. */
public record OverPlanMarkView(PlanPool pool, Instant since) {

    static OverPlanMarkView of(OverPlanMark mark) {
        return new OverPlanMarkView(mark.getPool(), mark.getSince());
    }
}
