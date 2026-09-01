package com.reelypops.rpenduser.stop;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The internal surface rpadminserver writes stop orders through (key-authed, no end-user JWT).
 *
 * <p>It sits here rather than in rpauth because rpauth has no link to the device registry and should not
 * grow one: rpauth is the authority on what an ACCOUNT is, and this is about what a MACHINE does. The shape
 * copies the client-release flow exactly — rpadminserver writes, rpenduser stores, the 60-second heartbeat
 * serves it to the fleet — so nothing here is a new idea.
 */
@RestController
@RequestMapping("/enduser/v1/internal")
public class AdminStopOrderController {

    private final StopOrderService stopOrders;

    public AdminStopOrderController(StopOrderService stopOrders) {
        this.stopOrders = stopOrders;
    }

    /** @param action SIGN_OUT (stop nothing), DISABLE (finish what is running) or KILL (stop now) */
    public record StopOrderRequest(StopAction action, String orderedBy) {
    }

    /** Tell this user's machines to stop. Re-issuing gets a new id, so a machine obeys again. */
    @PostMapping("/users/{userId}/stop-order")
    public StopDirective order(@PathVariable UUID userId, @RequestBody StopOrderRequest request,
                               jakarta.servlet.http.HttpServletRequest http) {
        return stopOrders.order(userId, request.action(), request.orderedBy(), sourceIp(http));
    }

    /**
     * Let this user's machines work again.
     *
     * <p>204 whether or not an order existed. The caller is re-activating an account, and whether a stop
     * order happened to be outstanding is not a condition it should have to handle — a "nothing to do" that
     * read as a failure would be a reason not to retry the one operation that must always succeed.
     *
     * <p>That matters more than it looks: this is the path that LETS SOMEBODY WORK AGAIN, and a plan where
     * lifting is less reliable than issuing is a plan for leaving customers stopped.
     */
    @DeleteMapping("/users/{userId}/stop-order")
    public ResponseEntity<Void> clear(@PathVariable UUID userId,
                                      @RequestParam(required = false) String clearedBy,
                                      jakarta.servlet.http.HttpServletRequest http) {
        stopOrders.clear(userId, clearedBy, sourceIp(http));
        return ResponseEntity.noContent().build();
    }

    /**
     * Where the call came from, as best this service can tell.
     *
     * <p>The LAST forwarded hop, because an ALB appends the real client to whatever the caller supplied —
     * so the first entry is whatever the caller chose. Same rule as everywhere else in the estate since
     * 01/09/2026, and the reason it is worth repeating in a comment is that four separate copies took the
     * first entry precisely because that is the obvious way to write it.
     */
    private static String sourceIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] hops = forwarded.split(",");
        return hops[hops.length - 1].trim();
    }
}
