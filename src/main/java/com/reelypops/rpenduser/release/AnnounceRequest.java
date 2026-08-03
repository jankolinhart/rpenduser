package com.reelypops.rpenduser.release;

import java.util.List;

/**
 * The admin GATE action body — the curated public blurb to attach when announcing the current published version:
 * short user-facing {@code highlights} + an {@code urgency}. Authored by the admin at the gate (never internal detail).
 */
public record AnnounceRequest(List<String> highlights, UpdateUrgency urgency) {
}
