package com.reelypops.rpenduser.release;

import java.util.List;

/**
 * The public update-announcement payload served to a client that is behind: the target {@code version} + the curated
 * blurb ({@code urgency} + short user-facing {@code highlights}). Carried on the heartbeat reply; never any internal
 * changelog. {@code null} when nothing is announced (or the client is up to date).
 */
public record ReleaseAnnouncement(String version, UpdateUrgency urgency, List<String> highlights) {
}
