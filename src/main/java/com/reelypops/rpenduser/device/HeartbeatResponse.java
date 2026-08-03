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
 */
public record HeartbeatResponse(boolean reportNeeded, boolean updateAvailable, String latestVersion,
                                ReleaseAnnouncement announcement) {
}
