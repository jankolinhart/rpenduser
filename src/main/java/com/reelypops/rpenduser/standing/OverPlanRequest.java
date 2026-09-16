package com.reelypops.rpenduser.standing;

import java.util.List;

/**
 * What a reader currently observes: every pool it finds over its ceiling, and nothing else.
 *
 * <p>It is a statement about ALL THREE pools, not a list of changes — a pool left out is asserted to be
 * inside its ceiling, and its clock is cleared. That is why the field is not optional in meaning even though
 * an absent or empty list is legal: "nothing is over" is a real and common observation, and it is the one
 * that stops the clocks.
 */
public record OverPlanRequest(List<PlanPool> over) {

    /** An absent list is "nothing is over", never "I have no opinion" — see the class comment. */
    public List<PlanPool> over() {
        return over == null ? List.of() : over;
    }
}
