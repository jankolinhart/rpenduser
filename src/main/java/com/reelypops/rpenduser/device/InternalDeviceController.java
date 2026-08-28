package com.reelypops.rpenduser.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.reelypops.rpenduser.drift.DriftForwardingService;
import com.reelypops.rpenduser.membership.MembershipForwardingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(InternalDeviceController.class);

    private final DeviceService devices;
    private final ClientVersionService clientVersion;
    private final DriftForwardingService driftForwarding;
    private final MembershipForwardingService membershipForwarding;

    public InternalDeviceController(DeviceService devices, ClientVersionService clientVersion,
                                    DriftForwardingService driftForwarding,
                                    MembershipForwardingService membershipForwarding) {
        this.devices = devices;
        this.clientVersion = clientVersion;
        this.driftForwarding = driftForwarding;
        this.membershipForwarding = membershipForwarding;
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
        Device stored = devices.applyReport(userId, deviceId, body.toString(), stateHash);
        // B6 follow-gating: forward the report's supportGroups[] as memberships to rpsupportgroup (best-effort — the
        // service swallows any failure, so a membership-forward fault never fails the report or moves the drift ack).
        membershipForwarding.forward(userId, body);
        // The response now ACKNOWLEDGES the drift that actually reached rpsupportgroup. A bare 202 told the client
        // "accepted" whether or not its drift was delivered, and the client then stopped re-asserting — so a fault
        // it had reported could sit on it forever while the cloud had never heard of it.
        // WHO ELSE IS WORKING THIS HANDLE. Two devices on one Instagram account do the same work twice at
        // twice the like-rate, which defeats the client's anti-bot pacing exactly two-fold — an
        // Instagram-detection risk, not a quota question. The client already told us the handle; this tells it
        // what we know back, on the reply to the call it was already making, so no new endpoint and no new
        // round trip. Empty when there is no conflict, so a client that ignores the field is unaffected.
        // NEVER FAILS THE REPORT, the same rule the membership forward above already follows: the snapshot is
        // stored by this point, and losing a conflict hint is a smaller harm than 500-ing a call whose work is
        // already done — the next heartbeat brings another report ~60s later.
        List<String> alsoWorking = List.of();
        try {
            if (stored != null && stored.getFocusedHandle() != null) {
                alsoWorking = devices.claimingSameHandle(userId, deviceId, stored.getFocusedHandle())
                        .stream().map(Device::getDeviceId).toList();
            }
        } catch (RuntimeException e) {
            log.warn("focused-handle conflict lookup failed for device {} — reporting no conflict", deviceId, e);
        }
        return ResponseEntity.accepted().body(
                new ReportResponse(driftForwarding.forward(userId, deviceId, body), alsoWorking));
    }

    /** The report verdict: which drift observations rpsupportgroup accepted, keyed as {@code kind|sgId|discriminator}. */
    /**
     * @param acknowledgedDrift which drift observations actually reached rpsupportgroup
     * @param handleAlsoWorkedBy the OTHER device ids of this user reporting the same in-focus handle — empty
     *                           when there is no conflict, and never null. Device ids are the user's own, so
     *                           this leaks nothing across accounts; the field is additive, so an older client
     *                           that ignores it behaves exactly as before.
     */
    public record ReportResponse(java.util.List<String> acknowledgedDrift,
                                 java.util.List<String> handleAlsoWorkedBy) {
    }

    /** A non-blank textual field of {@code body}, or {@code null} when absent/blank/non-textual. */
    private static String textField(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
