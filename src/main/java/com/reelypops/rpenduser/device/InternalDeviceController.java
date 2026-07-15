package com.reelypops.rpenduser.device;

import jakarta.validation.Valid;
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
 */
@RestController
@RequestMapping("/enduser/v1/internal")
public class InternalDeviceController {

    private final DeviceService devices;

    public InternalDeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/users/{userId}/devices")
    public DeviceResponse register(@PathVariable UUID userId, @Valid @RequestBody RegisterDeviceRequest req) {
        return DeviceResponse.of(devices.register(userId, req.deviceId(), req.platform()));
    }
}
