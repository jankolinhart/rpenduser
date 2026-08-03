package com.reelypops.rpenduser.release;

/**
 * The internal "a new verified-downloadable version is available" push body. rpadminserver (the AWS-integrated admin
 * hub) discovers the newest COMPLETE version in the channel's S3 artifact bucket and pushes it here so rpenduser can
 * hold it as the pending {@code publishedVersion}. rpenduser itself stays AWS-free.
 */
public record PublishedRequest(String version) {
}
