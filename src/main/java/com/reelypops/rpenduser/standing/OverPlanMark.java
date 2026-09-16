package com.reelypops.rpenduser.standing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * The standing mark that an account is over one of its plan ceilings, and SINCE WHEN.
 *
 * <h2>Why it exists</h2>
 * The operator's ruling of 16/09/2026 gives somebody seven days to choose what to give up before the app
 * stops offering them a way out: <i>"we give them a grace period of 7 days with daily reminders ... after 7
 * days we force them with an overlay to chose."</i> A grace period needs a start, and nothing in this system
 * recorded one — being over a ceiling is an ordinary state, reached by a plan changing underneath a
 * configuration rather than by anything going wrong, so no row anywhere said when it began.
 *
 * <h2>Why here and not on the client</h2>
 * Counted on a machine the seven days would restart on every reinstall, run differently on two computers of
 * one account, and be settable by moving the system clock. One row in the cloud is the same answer to every
 * reader.
 *
 * <h2>A standing mark, not a history</h2>
 * One row per {@code (user, pool)}, created the first time a reader observes that pool over and DELETED the
 * moment it observes it inside again — deliberately the same shape as rpsupportgroup's
 * {@code sg_membership_tombstone}. "Has this account been over since?" is a question a row that either
 * exists or does not cannot get wrong; deriving it from an append-only log of goings-over and comings-back
 * means comparing two timestamps, and gives the wrong answer outright when they tie.
 *
 * <p><strong>{@link #since} is never rewritten while the mark stands.</strong> That is the whole point. A
 * clock re-stamped on each read would sit at day zero for ever and the seventh day would never arrive —
 * which is the one failure of this feature that would look exactly like it working.</p>
 */
@Entity
@Table(name = "over_plan_mark")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OverPlanMark {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Which ceiling. Stored as its NAME rather than its ordinal: an ordinal would silently re-point every
     * existing row the day a constant is inserted into the middle of {@link PlanPool}, and a clock pointing
     * at the wrong pool is worse than no clock.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pool", nullable = false)
    private PlanPool pool;

    /** When this account was first observed over this ceiling. Written once, and then left alone. */
    @Column(name = "since", nullable = false)
    private Instant since;

    OverPlanMark(UUID userId, PlanPool pool, Instant since) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.pool = pool;
        this.since = since;
    }
}
