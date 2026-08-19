package com.reelypops.rpenduser.drift;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Parse the report's {@code drift[]}, forward each resolvable observation, and return the keys of those that
     * were <strong>actually accepted by rpsupportgroup</strong>.
     *
     * <p>The return value is the ACKNOWLEDGEMENT. Without it the client is told "202 accepted" whether or not the
     * drift reached its destination, and it then stops re-asserting: an unresolved fault sits on the client
     * forever while the cloud has never heard of it. That happened live on 16/08/2026 — a corrupt reference was
     * reported to a cloud that could not yet parse the kind, was dropped by {@link #mapKind}, and was recovered
     * only because an operator changed an unrelated setting and moved the report fingerprint by accident.</p>
     */
    public List<String> forward(UUID userId, String deviceId, JsonNode report) {
        JsonNode driftArray = report.get("drift");
        if (driftArray == null || !driftArray.isArray() || driftArray.isEmpty()) {
            return List.of();
        }
        Map<Long, String> igBySgId = sgIdToAccount(report);
        List<String> acknowledged = new ArrayList<>();
        for (JsonNode drift : driftArray) {
            String key = forwardOne(userId, deviceId, igBySgId, drift);
            if (key != null) {
                acknowledged.add(key);
            }
        }
        return acknowledged;
    }

    /**
     * The identity of one drift, computed the same way on both sides so an acknowledgement can be matched back to
     * the row that produced it: {@code kind|sgId|discriminator}.
     *
     * <p>Deliberately NOT the array index — the client matches this against its own stored rows, not against the
     * JSON it happened to send, and an index would silently mis-attribute the moment the ordering changed.</p>
     */
    public static String driftKey(String clientKind, Long sgId, String markerRole, String markerText,
                                  Integer markerWeekday, String nominatedOwnerHandle) {
        // ⚠️ MUST stay byte-identical to the desktop client's DriftAcknowledgementService.key(...) — this string
        // IS the contract that lets an acknowledgement find the row that produced it. A silent divergence would
        // acknowledge nothing and the client would re-assert forever (a literal NUL byte hid here once).
        //
        // The WEEKDAY is part of the identity: two client rows can share role AND text while describing different
        // days' banners (the per-weekday profile allows it), and without the weekday one acknowledgement would
        // match both rows — the unacknowledged one then re-asserts forever.
        String discriminator = nominatedOwnerHandle != null && !nominatedOwnerHandle.isBlank()
                ? nominatedOwnerHandle.trim()
                : (safe(markerRole) + " " + safe(markerText) + " w" + (markerWeekday == null ? "" : markerWeekday));
        return safe(clientKind) + "|" + (sgId == null ? "" : sgId) + "|" + discriminator;
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }

    /** Forward one observation; returns its {@link #driftKey} when rpsupportgroup accepted it, else {@code null}. */
    private String forwardOne(UUID userId, String deviceId, Map<Long, String> igBySgId, JsonNode drift) {
        Long sgId = longField(drift, "sgId");
        String clientKind = textField(drift, "kind");
        String kind = mapKind(clientKind);
        String igAccount = sgId == null ? null : igBySgId.get(sgId);
        if (igAccount == null || kind == null) {
            // NOT acknowledged — the client must keep re-asserting rather than assume it landed.
            log.debug("skipping undeliverable drift sgId={} kind={}", sgId, clientKind);
            return null;
        }
        DriftReportRequest req = new DriftReportRequest(kind, deviceId, userId,
                textField(drift, "nominatedOwnerHandle"), intField(drift, "agreePass"),
                intField(drift, "disagreePass"), intField(drift, "persistenceCount"),
                markerRole(drift), textField(drift, "markerText"), textField(drift, "detail"),
                intField(drift, "imageDistance"), intField(drift, "imageThreshold"),
                textField(drift, "evidencePostId"), binaryField(drift, "evidenceImage"),
                textField(drift, "evidenceImageHash"), intField(drift, "markerWeekday"));
        try {
            client.reportDrift(igAccount, req);
            return driftKey(clientKind, sgId, markerRole(drift), textField(drift, "markerText"),
                    intField(drift, "markerWeekday"), textField(drift, "nominatedOwnerHandle"));
        } catch (RuntimeException e) {
            log.warn("failed to forward {} drift for {}: {}", kind, igAccount, e.toString());
            return null; // unacknowledged — the client re-asserts it
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

    /**
     * Map the client drift kind onto the rpsupportgroup {@code DriftKind} name, or {@code null} when unrecognised.
     *
     * <p>⚠️ <strong>An unmapped kind is DROPPED here, silently.</strong> That is deliberate — rpenduser must not
     * forward a kind rpsupportgroup would reject — but it means this switch is a place a whole feature can go
     * missing: {@code marker-image-drift} was measured, retained and reported by the client, and fell into
     * {@code default -> null} on the way through, so no measurement ever reached rpsupportgroup. Any new client
     * drift kind must be added here as well as at both ends.</p>
     */
    private static String mapKind(String clientKind) {
        if (clientKind == null) {
            return null;
        }
        return switch (clientKind) {
            case "marker-disagree" -> "MARKER_DISAGREE";
            case "new-owner" -> "NEW_OWNER";
            case "marker-image-drift" -> "MARKER_IMAGE_DRIFT";
            case "marker-reference-corrupt" -> "MARKER_REFERENCE_CORRUPT";
            default -> null;
        };
    }

    /**
     * WHICH marker role this drift is about.
     *
     * <p>⚠️ The desktop client serialises it as <strong>{@code markerType}</strong> — that is the component name on
     * its {@code BackwardContractReport.Drift} record. Reading {@code markerRole} here returned null on every
     * observation, and a null role is not inert: the cloud's adoption matches references BY role, so it matched
     * nothing and silently left the vetted profile unchanged. A repair appeared to work while the live banner it
     * was supposed to add was quietly dropped. The admin card gave it away by saying "the marker reference"
     * instead of "the start reference".</p>
     *
     * <p>{@code markerRole} is accepted as a fallback so a future sender using the cloud-side name also works.</p>
     */
    private static String markerRole(JsonNode drift) {
        String type = textField(drift, "markerType");
        return type != null ? type : textField(drift, "markerRole");
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

    /**
     * A base64 picture from the client's report, passed through untouched.
     *
     * <p>Never re-encoded: the hash the cloud computes from these bytes is compared against LIVE images by every
     * client, and a re-compression would move it. The bytes forwarded are the exact bytes the client hashed.</p>
     */
    private static byte[] binaryField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        try {
            byte[] bytes = value.binaryValue();
            return bytes == null || bytes.length == 0 ? null : bytes;
        } catch (java.io.IOException e) {
            // Not decodable base64. The MEASUREMENT is still worth delivering — an administrator told "this banner
            // moved 15 bits" without a picture is far better off than one told nothing at all.
            log.warn("drift evidence image was not decodable base64 — forwarding the measurement without it");
            return null;
        }
    }
}
