package com.reelypops.rpenduser.standing;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The internal over-the-plan clock surface (key-authed, no end-user JWT), on {@code /enduser/v1/internal}.
 * Only the rpserver BFF reaches it, having validated the user's token and extracted the {@code sub} — the
 * user id in the path is that trusted subject, never anything a client chose.
 *
 * <h2>Why the one route is a POST that reads</h2>
 * It is an OBSERVATION, and observing is what starts and stops a clock. The BFF is the only thing that can
 * count all three pools — it composes rpauth's seats, this service's devices and rpsupportgroup's
 * memberships — so it is the only caller that can say which ceilings an account is over. It tells this
 * service what it found; this service answers with the clocks that now stand, including the ones that were
 * already running.
 *
 * <p>A GET would have to be paired with a separate write, and then two callers could disagree about whether
 * anyone had recorded the observation yet — which for a seven-day deadline means somebody's clock silently
 * never starting.
 */
@RestController
@RequestMapping("/enduser/v1/internal")
public class InternalOverPlanController {

    private final OverPlanMarkService marks;

    public InternalOverPlanController(OverPlanMarkService marks) {
        this.marks = marks;
    }

    /**
     * Record what the BFF currently sees and answer with this account's standing clocks.
     *
     * <p>Idempotent: calling it repeatedly with the same observation neither restarts a clock nor creates a
     * second one. An unreadable or absent body is "nothing is over", which CLEARS every clock — deliberately,
     * because the alternative is a request that fails open and leaves somebody counting down against a
     * ceiling they are no longer over.
     */
    @PostMapping("/users/{userId}/over-plan")
    public OverPlanResponse observe(@PathVariable UUID userId, @RequestBody(required = false) OverPlanRequest req) {
        Set<PlanPool> over = req == null || req.over().isEmpty()
                ? EnumSet.noneOf(PlanPool.class)
                : EnumSet.copyOf(req.over());
        return OverPlanResponse.of(marks.observe(userId, over));
    }
}
