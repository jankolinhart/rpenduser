package com.reelypops.rpenduser.device;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * End-user device registry API on {@code /enduser/v1/devices} (D3). The user is the authenticated JWT subject
 * (rpauth mints {@code sub} = user id); the client sends its own opaque device fingerprint. The registry never
 * sees a hostname. Registration is an idempotent heartbeat.
 */
@RestController
@RequestMapping("/enduser/v1/devices")
public class DeviceController {

    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping
    public DeviceResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegisterDeviceRequest req) {
        return DeviceResponse.of(devices.register(userId(jwt), req.deviceId(), req.platform()));
    }

    @GetMapping
    public List<DeviceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return devices.list(userId(jwt)).stream().map(DeviceResponse::of).toList();
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String deviceId) {
        return devices.remove(userId(jwt), deviceId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
