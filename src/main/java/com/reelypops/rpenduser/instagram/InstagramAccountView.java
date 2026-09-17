package com.reelypops.rpenduser.instagram;

import java.time.Instant;

/** One claimed account: the handle, where it was last seen, and since when it has been theirs. */
public record InstagramAccountView(String igHandle, String deviceId, Instant claimedAt, Instant lastSeenAt) {

    static InstagramAccountView of(InstagramAccount account) {
        return new InstagramAccountView(account.getIgHandle(), account.getDeviceId(),
                account.getClaimedAt(), account.getLastSeenAt());
    }
}
