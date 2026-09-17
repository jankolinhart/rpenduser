package com.reelypops.rpenduser.instagram;

/**
 * A claim: the handle being taken on, and the machine it was taken on at.
 *
 * <p>{@code deviceId} is optional and is a HINT — an account is an account-level fact that survives the
 * machine. It is carried so the Seat Map can draw the account under the computer it lives on, and for
 * nothing else.
 */
public record InstagramAccountClaim(String igHandle, String deviceId) {
}
