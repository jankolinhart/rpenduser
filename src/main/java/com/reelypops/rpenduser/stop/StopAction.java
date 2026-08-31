package com.reelypops.rpenduser.stop;

/**
 * What an administrator told this user's machines to do.
 *
 * <p>Four actions, one per button in the console. Three of them mean the account no longer works and differ
 * in how abruptly; the fourth stops nothing at all. None carries a reason, because the reason is the
 * operator's business and never the user's.
 *
 * <p>rpenduser does not act on any of them — it stores an order and serves it. The MEANING lives in the
 * client, which is the only thing with work to stop; what lives here is the one property this service must
 * itself understand, {@link #latches()}, because it decides how long an order keeps being served.
 */
public enum StopAction {

    /**
     * Sign out, and stop NOTHING — the Reset action.
     *
     * <p>The odd one out, and it earns its place here rather than in a channel of its own: without it a
     * reset's sign-out waits for the keep-alive refresh to be refused, up to eight minutes, while the other
     * three land in sixty seconds. Three of four actions being prompt and one being slow is the kind of
     * inconsistency that reads as a fault.
     *
     * <p>It carries no authority over work, and the client is the half that enforces that — see the
     * blocksWork rule there. A directive that signed out AND stopped work would turn the one benign action
     * into the harshest of them.
     */
    SIGN_OUT,

    /**
     * The account was disabled. Finish what is running, start nothing new.
     *
     * <p>The gentler of the two, and the one used for ordinary reasons — billing, cancellation, someone
     * leaving. A round in flight completes and writes its counts; nothing is torn out from under it.
     */
    DISABLE,

    /**
     * The kill switch. Stop now.
     *
     * <p>For the severe cases: a client hammering Instagram — which risks the CUSTOMER's own account, not
     * just our infrastructure — or an account known to be stolen. Work is stopped cooperatively so each
     * worker still writes down where it got to; "now" means "do not wait for the round to end", not
     * "abandon what you were doing".
     */
    KILL,

    /**
     * The account was removed. The same halt as a kill, and a different sentence.
     *
     * <p>Its own name ONLY so the user can be told the truth: a removed account is not a disabled one, and
     * being told it was disabled sends somebody to Support with the wrong question. Nothing in this service
     * treats it differently from {@link #KILL}, and nothing should.
     */
    REMOVE;

    /**
     * Does this order STAND until an administrator lifts it, or does it apply only to the machines that
     * hear it shortly after it is given?
     *
     * <p>Disable, Kill and Remove latch, and must: the account is not active, so an order still standing next
     * week is consistent with the decision that set it, and pressing Enable or Restore is what ends it.
     *
     * <p>Sign-out does not, and must not. Reset leaves the account ACTIVE and the user signing straight back
     * in, so a latched sign-out would be a trap — a laptop opened for the first time a fortnight later would
     * sign itself out sixty seconds after connecting, with no administrator anywhere near it and no button
     * to press to stop it happening again. Reset means <em>end the sessions that exist as I press this</em>,
     * which is exactly what rpauth does with the refresh tokens; this is the same instruction reaching the
     * machines, so it expires the same way.
     *
     * <p>Nothing is lost by letting it expire. A machine that was closed at the time still has its refresh
     * token revoked, so it signs out on its next keep-alive regardless. The directive only makes it prompt
     * for the machines that are actually running — an accelerator over a guarantee that already exists.
     */
    public boolean latches() {
        return this != SIGN_OUT;
    }
}
