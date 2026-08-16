package com.reelypops.rpenduser.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.reelypops.rpenduser.drift.DriftForwardingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal device-write surface (key-authed, no end-user JWT) on {@code /enduser/v1/internal}. The rpserver
 * BFF calls this to register (heartbeat) a device on behalf of the signed-in user — after it has validated the
 * user's access token and extracted the {@code sub} — so the desktop client reaches the registry only through
 * rpserver, never rpenduser directly, and rpenduser stays off the internet. Registration is the same idempotent
 * upsert as the public surface; the user id is the trusted {@code sub} the BFF passes in the path.
 *
 * <p>Also carries the M5.1 backward-contract up-channel: a frequent {@code /heartbeat} (liveness + a report-needed
 * decision) and an on-change {@code /report} (the full snapshot, stored opaquely).</p>
 */
@RestController
@RequestMapping("/enduser/v1/internal")
public class InternalDeviceController {

    private final DeviceService devices;
    private final ClientVersionService clientVersion;
    private final DriftForwardingService driftForwarding;

    public InternalDeviceController(DeviceService devices, ClientVersionService clientVersion,
                                    DriftForwardingService driftForwarding) {
        this.devices = devices;
        this.clientVersion = clientVersion;
        this.driftForwarding = driftForwarding;
    }

    @PostMapping("/users/{userId}/devices")
    public DeviceResponse register(@PathVariable UUID userId, @Valid @RequestBody RegisterDeviceRequest req) {
        return DeviceResponse.of(devices.register(userId, req.deviceId(), req.platform()));
    }

    /** M5.1 heartbeat — refresh liveness + tell the client whether to send a fresh full report; M5.3c — also flag an outdated client. */
    @PostMapping("/users/{userId}/devices/heartbeat")
    public HeartbeatResponse heartbeat(@PathVariable UUID userId, @Valid @RequestBody HeartbeatRequest req) {
        boolean reportNeeded = devices.heartbeat(userId, req.deviceId(), req.online(), req.stateHash());
        boolean updateAvailable = clientVersion.updateAvailable(req.appVersion());
        return new HeartbeatResponse(reportNeeded, updateAvailable, clientVersion.latestVersion(),
                updateAvailable ? clientVersion.announcement() : null);
    }

    /**
     * M5.1 report — store the full backward-contract snapshot verbatim (ignore-unknown/additive). The body carries
     * its own {@code deviceId} + {@code stateHash}; a body missing either is a 400. M5 re-vet consumer: after storing,
     * the report's {@code drift[]} is parsed + forwarded to rpsupportgroup (best-effort, never fails the report).
     */
    @PostMapping("/users/{userId}/devices/report")
    public ResponseEntity<ReportResponse> report(@PathVariable UUID userId, @RequestBody JsonNode body) {
        String deviceId = textField(body, "deviceId");
        String stateHash = textField(body, "stateHash");
        if (deviceId == null || stateHash == null) {
            return ResponseEntity.badRequest().build();
        }
        devices.applyReport(userId, deviceId, body.toString(), stateHash);
        // The response now ACKNOWLEDGES the drift that actually reached rpsupportgroup. A bare 202 told the client
        // "accepted" whether or not its drift was delivered, and the client then stopped re-asserting — so a fault
        // it had reported could sit on it forever while the cloud had never heard of it.
        return ResponseEntity.accepted().body(new ReportResponse(driftForwarding.forward(userId, deviceId, body)));
    }

    /** The report verdict: which drift observations rpsupportgroup accepted, keyed as {@code kind|sgId|discriminator}. */
    public record ReportResponse(java.util.List<String> acknowledgedDrift) {
    }

    /** A non-blank textual field of {@code body}, or {@code null} when absent/blank/non-textual. */
    private static String textField(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
