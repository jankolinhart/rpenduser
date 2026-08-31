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
        return of(device, presence, null, null);
    }

    /**
     * @param liveOrderId the user's outstanding stop order, or {@code null} when there is none
     */
    static AdminDeviceView of(Device device, DeviceService.Presence presence,
                              String liveOrderId, String liveAction) {
        return new AdminDeviceView(device.getDeviceId(), device.getPlatform(), device.getDeviceName(),
                presence, device.getFirstSeenAt(), device.getLastSeenAt(), device.getShutdownAt(),
                device.getFocusedHandle(), device.getFocusedHandleAt(), device.getAppVersion(),
                device.getStopAckedOrderId() == null ? null : device.getStopAckedOrderId().toString(),
                device.getStopAckedAt(),
                liveAction,
                // PENDING = there is an order and THIS machine has not acknowledged THIS one. Computed here
                // because this is the only place that holds both halves; asking the console to compare an
                // order id against an ack would put a rule in the surface that renders it.
                liveOrderId != null && !liveOrderId.equals(
                        device.getStopAckedOrderId() == null ? null : device.getStopAckedOrderId().toString()));
    }
}
