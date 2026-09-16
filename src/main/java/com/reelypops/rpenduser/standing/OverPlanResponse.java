package com.reelypops.rpenduser.standing;

import java.util.List;

/**
 * The clocks that stand after an observation, in {@link PlanPool} order.
 *
 * <p>Always the full set rather than a delta, for the same reason the request is: a caller holding a partial
 * answer would have to remember the rest, and the one thing this feature cannot afford is two readers with
 * different ideas of when somebody's seven days began.
 */
public record OverPlanResponse(List<OverPlanMarkView> marks) {

    static OverPlanResponse of(List<OverPlanMark> marks) {
        return new OverPlanResponse(marks.stream().map(OverPlanMarkView::of).toList());
    }
}
