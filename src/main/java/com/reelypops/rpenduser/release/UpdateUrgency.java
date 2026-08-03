package com.reelypops.rpenduser.release;

/**
 * M5.3c update-announcement urgency — how strongly a client should be nudged to update. Carried in the public
 * announcement payload; a security release uses {@link #URGENT} with a deliberately generic blurb.
 */
public enum UpdateUrgency {
    /** Routine — a newer version is available. */
    NORMAL,
    /** Recommended — the user should update soon. */
    RECOMMENDED,
    /** Urgent — e.g. important security/stability fixes; update as soon as possible. */
    URGENT
}
