package com.reelypops.rpenduser.stop;

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
 * An administrator has told this user's machines to stop.
 *
 * <p>ONE ROW PER USER, because the decision is about an ACCOUNT. A machine that registers after the order
 * was given is covered by it too, which is what an operator means when they stop a customer — per-device
 * would have to be re-issued for a laptop that came back tomorrow.
 *
 * <p><strong>Absence is "carry on", and that is the whole safety property.</strong> No row means no
 * instruction; an unreachable service, a failed query and a user nobody has ever acted on all produce the
 * same nothing, and nothing never stops anyone working. Storing "allowed" and inferring a stop from its
 * absence would turn every outage into a fleet-wide halt.
 */
@Entity
@Table(name = "user_stop_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserStopOrder {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Identifies THIS order, so a device can say which one it obeyed.
     *
     * <p>A re-issued order gets a new id and is therefore obeyed again — including by a machine that
     * acknowledged the previous one. That matters: an operator who presses stop a second time means it.
     */
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private StopAction action;

    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt;

    /** Who ordered it. For the record only — it never reaches the user. */
    @Column(name = "ordered_by", length = 120)
    private String orderedBy;

    private UserStopOrder(UUID userId, StopAction action, String orderedBy) {
        this.userId = userId;
        this.orderId = UUID.randomUUID();
        this.action = action;
        this.orderedAt = Instant.now();
        this.orderedBy = orderedBy;
    }

    /**
     * Is this order still to be served, given how long it has been sitting here?
     *
     * <p>A latching order always is. A momentary one — the sign-out a Reset issues — stops being served once
     * the window has passed, so it can never ambush a machine that connects long afterwards. See
     * {@link StopAction#latches()} for why the distinction is not an optimisation.
     */
    boolean stillStands(Instant now, java.time.Duration momentaryWindow) {
        return action.latches() || orderedAt.isAfter(now.minus(momentaryWindow));
    }

    static UserStopOrder issue(UUID userId, StopAction action, String orderedBy) {
        return new UserStopOrder(userId, action, orderedBy);
    }

    /**
     * Replace this order with a fresh one — a NEW id, so every machine obeys again.
     *
     * <p>Used when an operator escalates from DISABLE to KILL, or simply presses stop twice. Keeping the old
     * id would let a machine that had already acknowledged the gentler order ignore the harsher one.
     */
    void reissue(StopAction action, String orderedBy) {
        this.orderId = UUID.randomUUID();
        this.action = action;
        this.orderedAt = Instant.now();
        this.orderedBy = orderedBy;
    }
}
