package com.reelypops.rpenduser.device;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal admin surface for the device registry, consumed east-west by the rpadminserver BFF and gated by the
 * shared {@code X-Internal-Api-Key} (see the internal security chain). It exposes a per-user device tally for
 * the user-management dashboard's device column, plus the full device list for a single user (the drill-down).
 * Kept under {@code /enduser/v1/*} so it rides the existing ALB routing rule without a new one.
 */
@RestController
@RequestMapping("/enduser/v1/internal")
public class AdminDeviceController {

    private final DeviceService devices;

    public AdminDeviceController(DeviceService devices) {
        this.devices = devices;
    }

    /** Device count per user (one row per user that has at least one registered device). */
    @GetMapping("/devices/counts")
    public List<DeviceCount> counts() {
        return devices.counts();
    }

    /**
     * Every device registered to one user, with all the fields the registry holds about the machine —
     * including the presence band, which is computed here rather than stored.
     *
     * <p>ONE {@code now} FOR THE WHOLE LIST. Reading the clock per device would let two machines seen a
     * millisecond apart land in different bands, so a list could show a device as LIVE above one that is
     * STALE on the same last-seen. It is one answer to one question asked once.
     */
    @GetMapping("/users/{userId}/devices")
    public List<AdminDeviceView> forUser(@PathVariable UUID userId) {
        Instant now = Instant.now();
        return devices.list(userId).stream()
                .map(device -> AdminDeviceView.of(device, devices.presenceOf(device, now)))
                .toList();
    }
}
