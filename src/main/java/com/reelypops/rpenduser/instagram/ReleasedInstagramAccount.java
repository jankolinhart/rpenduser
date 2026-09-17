package com.reelypops.rpenduser.instagram;

import java.time.Instant;

/** One account the customer gave up, and when — what a machine reads to bring itself back into line. */
public record ReleasedInstagramAccount(String igHandle, Instant releasedAt) {

    static ReleasedInstagramAccount of(InstagramAccountRelease release) {
        return new ReleasedInstagramAccount(release.getIgHandle(), release.getReleasedAt());
    }
}
