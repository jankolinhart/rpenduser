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
        HandleHolder heldBy = null;
        try {
            if (stored != null && stored.getFocusedHandle() != null) {
                heldBy = devices.holderOf(userId, stored.getFocusedHandle())
                        // Silence when the caller IS the holder: a device is never told it is blocking itself.
                        .filter(holder -> !deviceId.equals(holder.getDeviceId()))
                        .map(HandleHolder::of)
                        .orElse(null);
            }
        } catch (RuntimeException e) {
            log.warn("focused-handle holder lookup failed for device {} — reporting no conflict", deviceId, e);
        }
        return ResponseEntity.accepted().body(
                new ReportResponse(driftForwarding.forward(userId, deviceId, body), heldBy));
    }

    /** The report verdict: which drift observations rpsupportgroup accepted, keyed as {@code kind|sgId|discriminator}. */
    /**
     * @param acknowledgedDrift which drift observations actually reached rpsupportgroup
     * @param handleHeldBy      the OTHER machine holding this device's in-focus handle, or {@code null} when
     *                          this device holds it, nobody does, or the holder has gone quiet past the TTL.
     *                          NULL IS THE SILENT CASE by design: a client acts only on a present holder and
     *                          can never read absence as a reason to stop, which is what keeps the guard
     *                          fail-OPEN (D16) when the cloud is unreachable. Additive, so a client that
     *                          ignores it behaves exactly as before.
     */
    public record ReportResponse(java.util.List<String> acknowledgedDrift, HandleHolder handleHeldBy) {
    }

    /**
     * The machine holding a handle, said plainly enough for a client to render without a second call.
     *
     * <p>Carries the user's OWN device id — nothing crosses accounts — plus what it runs on and when it was
     * last heard from, because "nothing is moving" must never be a mystery: the operator's rule is that the
     * user is told what holds the handle and how long ago it was seen.
     */
    public record HandleHolder(String deviceId, String platform, java.time.Instant lastSeenAt) {
        static HandleHolder of(Device device) {
            return new HandleHolder(device.getDeviceId(), device.getPlatform(), device.getLastSeenAt());
        }
    }

    /**
     * DELIBERATE TAKEOVER: this device asks for a handle another machine holds.
     *
     * <p>The user is in control rather than waiting on a timeout — the operator's rule of 28/08/2026. Every
     * other device of theirs releases the claim; the loser keeps RUNNING, it simply stops holding, and because
     * a re-report stamps its claim afresh it does not win the handle back.
     *
     * <p>The reply names who yielded so the caller can wait for them to stand down before it starts liking.
     * That pause is the point: starting immediately would put two machines on one account for a heartbeat —
     * precisely the double-rate this exists to prevent, just briefly and by request.
     */
    @PostMapping("/users/{userId}/devices/{deviceId}/handle-takeover")
    public ResponseEntity<TakeoverResponse> takeOverHandle(@PathVariable UUID userId,
                                                           @PathVariable String deviceId,
                                                           @RequestBody JsonNode body) {
        String handle = textField(body, "handle");
        if (handle == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(new TakeoverResponse(devices.takeOverHandle(userId, deviceId, handle)));
    }

    /** @param yieldedBy the device ids that gave the handle up — empty when nobody else held it. */
    public record TakeoverResponse(java.util.List<String> yieldedBy) {
    }

    /** A non-blank textual field of {@code body}, or {@code null} when absent/blank/non-textual. */
    private static String textField(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
