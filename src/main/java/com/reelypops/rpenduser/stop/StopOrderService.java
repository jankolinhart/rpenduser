package com.reelypops.rpenduser.stop;

import com.reelypops.rpenduser.device.Device;
import com.reelypops.rpenduser.device.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * @return the order as the machines will see it
     */
    @Transactional
    public StopDirective order(UUID userId, StopAction action, String orderedBy) {
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
     * <p>Empty when there is no order, and ALSO when this device has already acknowledged the current one —
     * a machine that has stopped does not need telling again every minute, and repeating the instruction
     * would make a stopped client re-run its stop on every beat.
     */
    @Transactional(readOnly = true)
    public Optional<StopDirective> directiveFor(UUID userId, String deviceId) {
        return orders.findById(userId)
                .filter(order -> !alreadyObeyed(userId, deviceId, order))
                .map(StopDirective::of);
    }

    private boolean alreadyObeyed(UUID userId, String deviceId, UserStopOrder order) {
        return devices.findByUserIdAndDeviceId(userId, deviceId)
                .map(device -> order.getOrderId().equals(device.getStopAckedOrderId()))
                .orElse(false);
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
