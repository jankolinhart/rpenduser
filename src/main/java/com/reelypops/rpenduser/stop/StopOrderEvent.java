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
 * ONE THING THAT HAPPENED TO A STOP ORDER — issued, or lifted. APPEND-ONLY.
 *
 * <h2>Why this exists</h2>
 * {@link UserStopOrder} is one mutable row per user. Re-issuing overwrites it in place, and {@code clear()}
 * — the <em>Enable</em> button — deletes it. So <strong>the act of putting a customer back to work destroyed
 * the only evidence of what had been done to them</strong>, and an escalation from DISABLE to KILL left no
 * trace that the gentler order was ever given.
 *
 * <p>That is backwards. A stop is the most consequential thing an administrator can do to a paying customer,
 * and the record of it must outlive the decision that set it. Worse, in the one scenario this whole
 * observability effort is built around — a stop issued out-of-band with a stolen key — the RECOVERY action
 * is what erases the evidence of the attack.
 *
 * <h2>No mutators, on purpose</h2>
 * There is no setter, no {@code update}, and the repository exposes no delete. A row is written once and
 * read for ever. An audit an attacker can edit is not an audit, and an audit the ordinary happy path
 * deletes is worse than one nobody wrote.
 */
@Entity
@Table(name = "stop_order_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StopOrderEvent {

    /** What happened to the order. Not what the order SAYS — that is {@link #action}. */
    public enum Event { ISSUED, CLEARED }

    @Id
    @Column(nullable = false)
    private UUID id;

    /** Which order. Survives the order row itself, which is the point. */
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StopAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Event event;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /**
     * The label the caller supplied for itself.
     *
     * <p>Named {@code claimedBy} rather than {@code orderedBy} because nothing verifies it: this surface is
     * authenticated by a shared service key with no per-caller identity, so the field is a string somebody
     * typed. A column called "orderedBy" invites a reader to treat it as established, which is exactly the
     * mistake rpauth's audit made with its own actor.
     */
    @Column(name = "claimed_by", length = 200)
    private String claimedBy;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;

    static StopOrderEvent of(UserStopOrder order, Event event, String claimedBy, String sourceIp) {
        StopOrderEvent e = new StopOrderEvent();
        e.id = UUID.randomUUID();
        e.orderId = order.getOrderId();
        e.userId = order.getUserId();
        e.action = order.getAction();
        e.event = event;
        e.occurredAt = Instant.now();
        e.claimedBy = claimedBy;
        e.sourceIp = sourceIp;
        return e;
    }
}
