package com.reelypops.rpenduser.device;

import com.reelypops.rpenduser.release.ReleaseAnnouncement;

/**
 * M5.1 heartbeat reply: whether the client should follow up with a full backward-contract report — {@code true}
 * when its current {@code stateHash} differs from the last report the backend stored (so the larger payload is
 * only sent when something actually changed).
 *
 * <p>M5.3c: also whether the client is behind the announced latest release ({@code updateAvailable}) + that
 * {@code latestVersion}, and — when behind and a blurb has been announced — the {@code announcement} payload
 * (urgency + curated highlights) to surface on the "update available" affordance ({@code null} otherwise).
 *
 * <p>31/08/2026: it also carries {@code stopDirective} — an administrator telling this user's machines to
 * stop. NULL MEANS NO INSTRUCTION, and every client must read it that way. A missing field, an older
 * backend, a null body and an unreachable cloud all present as null, so "we did not hear an order" and
 * "there is no order" are deliberately indistinguishable. That is what keeps one outage from stopping every
 * customer at once.
 */
public record HeartbeatResponse(boolean reportNeeded, boolean updateAvailable, String latestVersion,
                                ReleaseAnnouncement announcement,
                               com.reelypops.rpenduser.stop.StopDirective stopDirective) {
}
