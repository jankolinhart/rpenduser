package com.reelypops.rpenduser.security;

import java.time.Instant;
import java.util.UUID;

/**
 * ONE SHAPE FOR SOMETHING SECURITY-RELEVANT THAT HAPPENED — the same shape rpauth speaks.
 *
 * <p>Deliberately duplicated rather than shared through a library. These are two services with separate
 * deployables and no common artifact, and a shared jar would couple their release cycles for the sake of
 * ten fields. The contract that matters is the JSON on the wire, and rpadminserver reads both.
 *
 * <p>If a third service joins, THAT is the moment to extract a module — not before.
 *
 * @param actorAuthenticated whether {@link #actor} was PROVED or merely CLAIMED. Here it is always false:
 *        this service's stop orders carry a free-text label supplied by a caller holding a shared key with
 *        no per-caller identity. Saying so on every row is the whole reason the field exists — a console
 *        that rendered a claim like a fact would, after an incident, exonerate the path actually used.
 */
public record SecurityEvent(Instant occurredAt,
                            String service,
                            String type,
                            String severity,
                            UUID subjectUserId,
                            String actor,
                            boolean actorAuthenticated,
                            String sourceIp,
                            String userAgent,
                            String detail) {
}
