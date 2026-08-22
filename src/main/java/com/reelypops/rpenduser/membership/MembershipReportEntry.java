package com.reelypops.rpenduser.membership;

/**
 * One element of the outbound membership-report body forwarded to rpsupportgroup (B6 follow-gating): the group's IG
 * account, the user's own IG handle acting in it, and the raw follow signal. Mirrors the wire WRITE element
 * {@code {igHandle, igAccount, followingStatus}}; the body is a JSON array of these.
 *
 * <p>{@code followingStatus} is the CLIENT'S raw string ({@code following|not_following|unknown|requested}), never a
 * boolean: rpsupportgroup maps {@code following}→FOLLOWING(+stamp), {@code not_following}→NOT_FOLLOWING(flip), and
 * treats {@code unknown}/{@code requested}/blank as a NO-OP so a lossy signal can never flip stored status. The
 * app-wide non-null Jackson omits a null status on the wire, which rpsupportgroup also reads as the NO-OP case.</p>
 */
public record MembershipReportEntry(String igHandle, String igAccount, String followingStatus) {
}
