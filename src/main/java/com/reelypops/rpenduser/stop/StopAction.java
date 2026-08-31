package com.reelypops.rpenduser.stop;

/**
 * What an administrator told this user's machines to do.
 *
 * <p>Two actions, and the difference is only how abruptly work ends. Both mean the account no longer works;
 * neither carries a reason, because the reason is the operator's business and never the user's.
 */
public enum StopAction {

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
    KILL
}
