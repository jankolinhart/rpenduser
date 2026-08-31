package com.reelypops.rpenduser.stop;

/**
 * The instruction carried down to a running machine on its 60-second heartbeat.
 *
 * <p>Null on the reply means NO INSTRUCTION, and every client must read it that way. A missing field, an
 * older backend, a null body and an unreachable cloud all present as null, so "we did not hear an order"
 * and "there is no order" are deliberately the same thing — which is what keeps one outage from stopping
 * every customer at once.
 *
 * @param orderId what the machine acknowledges once it has obeyed, so an operator can tell a stop that
 *                LANDED from one that is merely pending on a machine that was closed at the time
 */
public record StopDirective(String orderId, StopAction action) {

    static StopDirective of(UserStopOrder order) {
        return new StopDirective(order.getOrderId().toString(), order.getAction());
    }
}
