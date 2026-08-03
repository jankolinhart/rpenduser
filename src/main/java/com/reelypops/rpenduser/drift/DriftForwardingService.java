package com.reelypops.rpenduser.drift;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M5 re-vet consumer (client→cloud up-channel): rpenduser stores the backward-contract report opaquely, but now also
 * PARSES its {@code drift[]} and forwards each observation to rpsupportgroup. Each drift carries a client-LOCAL
 * {@code sgId} (not a cloud key), resolved to the group's IG account via the report's own {@code supportGroups[]}
 * ({@code sgId → accountName}). Best-effort: an unresolvable/malformed drift is skipped and a forwarding failure is
 * logged, never propagated — the report is already stored, and drift is dumb-eager + deduped downstream.
 */
@Service
public class DriftForwardingService {

    private static final Logger log = LoggerFactory.getLogger(DriftForwardingService.class);

    private final SupportGroupDriftClient client;

    public DriftForwardingService(SupportGroupDriftClient client) {
        this.client = client;
    }

    /** Parse the report's {@code drift[]} and forward each resolvable observation to rpsupportgroup. */
    public void forward(UUID userId, String deviceId, JsonNode report) {
        JsonNode driftArray = report.get("drift");
        if (driftArray == null || !driftArray.isArray() || driftArray.isEmpty()) {
            return;
        }
        Map<Long, String> igBySgId = sgIdToAccount(report);
        for (JsonNode drift : driftArray) {
            forwardOne(userId, deviceId, igBySgId, drift);
        }
    }

    private void forwardOne(UUID userId, String deviceId, Map<Long, String> igBySgId, JsonNode drift) {
        Long sgId = longField(drift, "sgId");
        String kind = mapKind(textField(drift, "kind"));
        String igAccount = sgId == null ? null : igBySgId.get(sgId);
        if (igAccount == null || kind == null) {
            log.debug("skipping undeliverable drift sgId={} kind={}", sgId, textField(drift, "kind"));
            return;
        }
        DriftReportRequest req = new DriftReportRequest(kind, deviceId, userId,
                textField(drift, "nominatedOwnerHandle"), intField(drift, "agreePass"),
                intField(drift, "disagreePass"), intField(drift, "persistenceCount"));
        try {
            client.reportDrift(igAccount, req);
        } catch (RuntimeException e) {
            log.warn("failed to forward {} drift for {}: {}", kind, igAccount, e.toString());
        }
    }

    /** Build the report's client-local {@code sgId → IG account} map from its {@code supportGroups[]}. */
    private static Map<Long, String> sgIdToAccount(JsonNode report) {
        Map<Long, String> map = new HashMap<>();
        JsonNode groups = report.get("supportGroups");
        if (groups != null && groups.isArray()) {
            for (JsonNode group : groups) {
                Long sgId = longField(group, "sgId");
                String account = textField(group, "accountName");
                if (sgId != null && account != null) {
                    map.put(sgId, account);
                }
            }
        }
        return map;
    }

    /** Map the client drift kind onto the rpsupportgroup {@code DriftKind} name, or {@code null} when unrecognised. */
    private static String mapKind(String clientKind) {
        if (clientKind == null) {
            return null;
        }
        return switch (clientKind) {
            case "marker-disagree" -> "MARKER_DISAGREE";
            case "new-owner" -> "NEW_OWNER";
            default -> null;
        };
    }

    private static String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static Long longField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToLong() ? value.asLong() : null;
    }

    private static Integer intField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }
}
