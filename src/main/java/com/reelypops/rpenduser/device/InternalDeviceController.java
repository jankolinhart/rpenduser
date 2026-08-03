package com.reelypops.rpenduser.device;

import com.fasterxml.jackson.databind.JsonNode;
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

    public InternalDeviceController(DeviceService devices, ClientVersionService clientVersion) {
        this.devices = devices;
        this.clientVersion = clientVersion;
    }

    @PostMapping("/users/{userId}/devices")
    public DeviceResponse register(@PathVariable UUID userId, @Valid @RequestBody RegisterDeviceRequest req) {
        return DeviceResponse.of(devices.register(userId, req.deviceId(), req.platform()));
    }

    /** M5.1 heartbeat — refresh liveness + tell the client whether to send a fresh full report; M5.3c — also flag an outdated client. */
    @PostMapping("/users/{userId}/devices/heartbeat")
    public HeartbeatResponse heartbeat(@PathVariable UUID userId, @Valid @RequestBody HeartbeatRequest req) {
        boolean reportNeeded = devices.heartbeat(userId, req.deviceId(), req.online(), req.stateHash());
        return new HeartbeatResponse(reportNeeded, clientVersion.updateAvailable(req.appVersion()),
                clientVersion.latestVersion());
    }

    /**
     * M5.1 report — store the full backward-contract snapshot verbatim (ignore-unknown/additive). The body carries
     * its own {@code deviceId} + {@code stateHash}; a body missing either is a 400.
     */
    @PostMapping("/users/{userId}/devices/report")
    public ResponseEntity<Void> report(@PathVariable UUID userId, @RequestBody JsonNode body) {
        String deviceId = textField(body, "deviceId");
        String stateHash = textField(body, "stateHash");
        if (deviceId == null || stateHash == null) {
            return ResponseEntity.badRequest().build();
        }
        devices.applyReport(userId, deviceId, body.toString(), stateHash);
        return ResponseEntity.accepted().build();
    }

    /** A non-blank textual field of {@code body}, or {@code null} when absent/blank/non-textual. */
    private static String textField(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
