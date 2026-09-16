package com.reelypops.rpenduser.standing;

/**
 * The three ceilings a subscription grants, which are counted three different ways over three different
 * kinds of row — machines, distinct handles, and membership rows. They are independent everywhere else in
 * this system, so they run independent clocks here too: the seat taken on Tuesday does not inherit the
 * deadline of the group joined last week (operator's ruling, 16/09/2026 — "per pool, counted separately").
 *
 * <p>An enum rather than a free string because the value is a database key: a caller that could invent
 * {@code "GROUPS"} would create a second clock for a pool that already has one, and nothing would fail.
 */
public enum PlanPool {

    /** Machines holding a live seat. Ends only by a deliberate act, never by closing the app. */
    SEATS,

    /** Distinct Instagram handles this account has set up. */
    IG_ACCOUNTS,

    /** Support-group connections: ROWS, so one group reached through two handles is two. */
    GROUP_CONNECTIONS
}
