package com.reelypops.rpenduser.membership;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * B6 follow-gating up-channel (client→cloud): rpenduser stores the M5.1 report opaquely but also PARSES its
 * {@code supportGroups[]} and forwards each membership to rpsupportgroup — the {@code sg_membership} record home — as
 * a per-row idempotent upsert. onAccount→igHandle, accountName→igAccount.
 *
 * <p>The follow signal forwarded is the client's RAW {@code followingStatus} string, never a boolean: the legacy
 * boolean {@code following} is lossy ({@code unknown} and {@code not_following} both collapse to {@code false}), so a
 * legacy {@code false} maps to {@code unknown} (a NO-OP downstream) and NEVER to {@code not_following} (which would
 * flip stored status). Best-effort: a parse or forward failure is logged, never propagated — the report is already
 * stored, and absence of a group is never treated as an unfollow downstream.</p>
 */
@Service
public class MembershipForwardingService {

    private static final Logger log = LoggerFactory.getLogger(MembershipForwardingService.class);

    private final SupportGroupMembershipClient client;

    public MembershipForwardingService(SupportGroupMembershipClient client) {
        this.client = client;
    }

    /** Parse the report's {@code supportGroups[]} and forward the memberships (best-effort; never throws). */
    public void forward(UUID userId, JsonNode report) {
        try {
            List<MembershipReportEntry> memberships = parse(report);
            if (memberships.isEmpty()) {
                return;
            }
            client.reportMemberships(userId, memberships);
        } catch (RuntimeException e) {
            log.warn("failed to forward memberships for user {}: {}", userId, e.toString());
        }
    }

    /** Build the upsert array from {@code supportGroups[]}: onAccount→igHandle, accountName→igAccount, raw status. */
    private static List<MembershipReportEntry> parse(JsonNode report) {
        List<MembershipReportEntry> out = new ArrayList<>();
        JsonNode groups = report.get("supportGroups");
        if (groups == null || !groups.isArray()) {
            return out;
        }
        for (JsonNode group : groups) {
            String igHandle = textField(group, "onAccount");
            String igAccount = textField(group, "accountName");
            if (igHandle == null || igAccount == null) {
                continue; // a membership needs both the acting handle and the group account
            }
            out.add(new MembershipReportEntry(igHandle, igAccount, followingStatus(group)));
        }
        return out;
    }

    /**
     * The RAW follow signal for one group. Prefer the client's {@code followingStatus} string; otherwise fall back to
     * the legacy boolean {@code following} for an older client — {@code true}→{@code following}, but {@code false}→
     * {@code unknown} (NOT {@code not_following}): a legacy false is lossy and must never cause a stored-status flip.
     * {@code null} when neither is present → omitted by non-null Jackson → a NO-OP downstream.
     */
    private static String followingStatus(JsonNode group) {
        String explicit = textField(group, "followingStatus");
        if (explicit != null) {
            return explicit;
        }
        JsonNode legacy = group.get("following");
        if (legacy != null && legacy.isBoolean()) {
            return legacy.asBoolean() ? "following" : "unknown";
        }
        return null;
    }

    private static String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
