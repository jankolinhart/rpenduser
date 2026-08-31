package com.reelypops.rpenduser.device;

import java.time.Instant;

/**
 * One machine, as the admin console needs to see it.
 *
 * <p>It answers the three questions support is actually asked — <em>which machine is that, is it running
 * right now, and what is it doing?</em> — without a second call. The identity half is the opaque
 * fingerprint plus the two labels beside it; the liveness half is a band, its timestamps and the goodbye
 * that may have produced it; the activity half is the Instagram handle this machine has in focus.
 *
 * <p>There is still no raw hardware id and no hostname read off the machine (D3): {@code deviceName} is the
 * user's own answer, typed on a screen they were looking at.
 *
 * @param deviceName what the user calls it, or {@code null} — the console falls back to {@code platform},
 *                   because an old row must still be identifiable as something.
 * @param presence   LIVE / STALE / OFFLINE. A CONVENIENCE, never the truth — which is why
 *                   {@code lastSeenAt} travels beside it and the console shows the age either way.
 * @param shutdownAt when this client last said it was closing cleanly, or {@code null}. Explains an
 *                   OFFLINE that arrived early; its absence explains nothing, because a crash says nothing.
 * @param focusedHandle the Instagram account this machine is working, or {@code null}. With the presence
 *                   band beside it this is what turns "the user says nothing is happening" into an answer.
 * @param appVersion the client build it last reported, or {@code null}.
 *
 * <p>The {@code online} flag the registry also stores is deliberately NOT here. It is a Google-204
 * reachability probe rather than client liveness, and sitting in a column called "online" next to a
 * presence band it would be read as the same question with a second, disagreeing answer.
 */
public record AdminDeviceView(String deviceId, String platform, String deviceName,
                              DeviceService.Presence presence, Instant firstSeenAt, Instant lastSeenAt,
                              Instant shutdownAt, String focusedHandle, Instant focusedHandleAt,
                              String appVersion, String stopAckedOrderId, Instant stopAckedAt,
                              String stopAction, boolean stopPending) {

    static AdminDeviceView of(Device device, DeviceService.Presence presence) {
        return of(device, presence, null, Instant.now());
    }

    /**
     * @param standing the user's outstanding stop order, or {@code null} when there is none. Passed whole
     *                 rather than as loose fields because two of the rules below need its ACTION, and an
     *                 action reduced to a string on the way in cannot answer whether it latches.
     * @param now       ONE read of the clock for the whole list, passed in for the same reason the presence
     *                  band's is: two machines compared against two different reads could land on different
     *                  sides of the same expiry, so one list could contradict itself.
     */
    static AdminDeviceView of(Device device, DeviceService.Presence presence,
                              com.reelypops.rpenduser.stop.UserStopOrder standing, Instant now) {
        String acked = device.getStopAckedOrderId() == null ? null : device.getStopAckedOrderId().toString();
        String liveOrderId = standing == null ? null : standing.getOrderId().toString();
        boolean latching = standing != null && standing.getAction().latches();

        // AN ACKNOWLEDGEMENT MEANS NOTHING WITHOUT AN ORDER TO HAVE OBEYED.
        //
        // The ack columns outlive the order: pressing Enable deletes the order row, and a momentary sign-out
        // expires by itself, and neither touches the device. So a machine that obeyed a disable an hour ago,
        // was re-enabled, and had been working happily ever since still carried `stopAckedAt` on the wire —
        // and the console rendered it, in red, for ever. The operator met exactly that: a LIVE machine, seen
        // just now, working a handle, under a badge reading "stopped 39m ago". It was not stopped.
        boolean answersTheStandingOrder = liveOrderId != null && liveOrderId.equals(acked);

        // AND A SIGN-OUT IS A MOMENT, NOT A STATE. Once a machine has obeyed one there is nothing left to
        // report: the account is active, no work was touched, and the only consequence was a login prompt
        // the user has very likely already answered. Reporting it anyway produced a row reading "signed out
        // 1m ago" beside a LIVE badge — and LIVE means the machine is sending heartbeats, which needs an
        // access token, which means it is signed IN. The two halves of one row contradicted each other.
        //
        // A disable or a kill keeps its acknowledgement, because those describe a condition that is still
        // true and an operator needs to see which machines it has reached.
        boolean worthReportingAfterwards = answersTheStandingOrder && latching;

        // PENDING = there is an order and THIS machine has not acknowledged THIS one. Computed here because
        // this is the only place that holds both halves; asking the console to compare an order id against
        // an ack would put a rule in the surface that renders it.
        //
        // EXCEPT that a momentary order cannot be pending on a machine that has not been seen for longer
        // than the order will live. The console honestly tells an unreached machine that it "stops if it
        // returns" — but a sign-out will NOT be waiting when it returns, because it expires. Two machines
        // last seen 43 days ago were being promised a sign-out that could never happen.
        boolean unreachableBeforeItExpires = !latching && (device.getLastSeenAt() == null
                || device.getLastSeenAt().isBefore(
                        now.minus(com.reelypops.rpenduser.stop.StopOrderService.MOMENTARY_WINDOW)));
        boolean pending = liveOrderId != null && !liveOrderId.equals(acked) && !unreachableBeforeItExpires;

        return new AdminDeviceView(device.getDeviceId(), device.getPlatform(), device.getDeviceName(),
                presence, device.getFirstSeenAt(), device.getLastSeenAt(), device.getShutdownAt(),
                device.getFocusedHandle(), device.getFocusedHandleAt(), device.getAppVersion(),
                worthReportingAfterwards ? acked : null,
                worthReportingAfterwards ? device.getStopAckedAt() : null,
                standing == null ? null : standing.getAction().name(),
                pending);
    }
}
