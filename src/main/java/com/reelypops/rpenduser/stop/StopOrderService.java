package com.reelypops.rpenduser.stop;

import com.reelypops.rpenduser.device.Device;
import com.reelypops.rpenduser.device.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issuing, serving and — the half that is easy to build weakly — LIFTING a stop order.
 *
 * <h2>Lifting must be at least as reliable as issuing</h2>
 * Three independent reviewers of this design, given three different slices and told only to attack them,
 * each returned the same fault: every path that STOPS a customer was carefully defended and the path that
 * lets them work again was an afterthought. One found that an unreachable service on the way to "Enable"
 * would leave a customer stopped indefinitely; another that Enable and Restore had been left untouched
 * altogether, so Kill-then-Enable left the account signing in normally while every machine stopped again on
 * each 60-second beat.
 *
 * <p>So {@link #clear} is a first-class operation, it is idempotent, and the caller runs it BEFORE
 * re-activating the account — the reverse of the order used to stop. In both directions a half-completed
 * operation leaves the customer able to work.
 */
@Service
public class StopOrderService {

    /**
     * How long a momentary order — the sign-out a Reset issues — keeps being served.
     *
     * <p>Ten minutes against a sixty-second beat: long enough that every machine which was running when the
     * operator pressed the button hears it several times over, including through a short network drop, and
     * short enough that it is thoroughly over before anybody could mistake it for a state.
     */
    public static final Duration MOMENTARY_WINDOW = Duration.ofMinutes(10);

    private final UserStopOrderRepository orders;
    private final DeviceRepository devices;

    public StopOrderService(UserStopOrderRepository orders, DeviceRepository devices) {
        this.orders = orders;
        this.devices = devices;
    }

    /**
     * Tell this user's machines to stop.
     *
     * <p>Re-issuing replaces the order with a new id rather than leaving the old one, so a machine that
     * already obeyed a DISABLE obeys a following KILL. An operator who presses stop twice means it.
     *
     * <p><strong>Except in one direction: a momentary order never displaces a latching one.</strong>
     * Reset is offered on every account including a disabled one, and it issues a sign-out — so without
     * this, pressing Reset on a disabled customer would overwrite their DISABLE with an order that stops
     * nothing, and every one of their machines would go back to work on its next beat. An administrator
     * ending somebody's sessions has plainly not decided to un-stop them, so the standing order wins and
     * the sign-out is folded into it: the harsher instruction is already on its way, and it signs them out
     * too. Only {@link #clear} lifts a stop, which is the single door rule 4 asks for.
     *
     * @return the order as the machines will see it
     */
    @Transactional
    public StopDirective order(UUID userId, StopAction action, String orderedBy) {
        Optional<UserStopOrder> standing = standingOrder(userId, Instant.now());
        if (!action.latches() && standing.filter(o -> o.getAction().latches()).isPresent()) {
            return StopDirective.of(standing.get());
        }
        UserStopOrder order = orders.findById(userId)
                .map(existing -> {
                    existing.reissue(action, orderedBy);
                    return existing;
                })
                .orElseGet(() -> UserStopOrder.issue(userId, action, orderedBy));
        return StopDirective.of(orders.save(order));
    }

    /**
     * Let this user's machines work again.
     *
     * <p>IDEMPOTENT, and deliberately silent about whether there was anything to clear. The caller is
     * re-activating an account; whether a stop order happened to exist is not a condition it should have to
     * handle, and a "nothing to do" that read as a failure would be a reason not to retry the one operation
     * that must always succeed.
     */
    @Transactional
    public void clear(UUID userId) {
        orders.deleteById(userId);
    }

    /**
     * What this device should be told, or empty.
     *
     * <p><strong>SENT ON EVERY BEAT FOR AS LONG AS THE ORDER STANDS</strong>, including to a machine that
     * has already acknowledged it. That is not chatter — it is what makes the instruction reversible.
     *
     * <p>This originally stopped repeating once a device acknowledged, on the reasoning that a stopped
     * machine does not need telling twice. Designing the client half showed why that is wrong. The client
     * must distinguish three answers: <em>stop</em>, <em>you may work</em>, and <em>I could not ask</em> —
     * and an outage has to land on the third, leaving whatever state the machine already had. That means
     * the SILENCE of an outage and the SILENCE of "no order" cannot both mean the same thing, so an
     * outstanding order has to keep saying so. Suppressing it after an ack made a stopped-and-acked machine
     * indistinguishable from a released one, and it would have resumed work the moment it was told nothing
     * — which is precisely what an unreachable cloud looks like.
     *
     * <p>The client applies an order once per {@code orderId} and ignores the repeats, so nothing is re-run.
     * The acknowledgement is kept for the console alone: it answers "did it land", never "should I stop".
     */
    @Transactional(readOnly = true)
    public Optional<StopDirective> directiveFor(UUID userId) {
        return standingOrder(userId, Instant.now()).map(StopDirective::of);
    }

    /**
     * The order that is still in force for this user, or empty.
     *
     * <p>The ONE place that answers "is there an order", so the fleet and the console cannot disagree about
     * it. A momentary order that has outlived its window is not in force, and reading the row directly would
     * have let the console keep reporting a sign-out as outstanding for ever.
     */
    @Transactional(readOnly = true)
    public Optional<UserStopOrder> standingOrder(UUID userId, Instant now) {
        return orders.findById(userId).filter(order -> order.stillStands(now, MOMENTARY_WINDOW));
    }

    /**
     * A machine reporting that it has carried the order out.
     *
     * <p>Recorded against the ORDER it obeyed, not as a bare flag, so a later order is not satisfied by an
     * earlier acknowledgement. An ack for an order that no longer exists, or for a different one, is
     * ignored rather than refused: it is a machine telling us about a decision we have since changed our
     * mind about, which is stale news, not an error.
     */
    @Transactional
    public void acknowledge(UUID userId, String deviceId, String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        UUID acked;
        try {
            acked = UUID.fromString(orderId.trim());
        } catch (IllegalArgumentException malformed) {
            return;
        }
        boolean current = orders.findById(userId)
                .map(order -> order.getOrderId().equals(acked))
                .orElse(false);
        if (!current) {
            return;
        }
        devices.findByUserIdAndDeviceId(userId, deviceId).ifPresent(device -> {
            device.acknowledgeStop(acked);
            devices.save(device);
        });
    }
}
