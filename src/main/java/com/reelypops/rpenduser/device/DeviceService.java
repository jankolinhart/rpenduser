package com.reelypops.rpenduser.device;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Device-registry logic (D3). Registration is an idempotent upsert keyed on (user, device): a returning
 * device is a heartbeat, not a duplicate. Owned by rpenduser; rppayment reads it for seat enforcement.
 */
@Service
public class DeviceService {

    private final DeviceRepository devices;

    public DeviceService(DeviceRepository devices) {
        this.devices = devices;
    }

    @Transactional
    public Device register(UUID userId, String deviceId, String platform) {
        return devices.findByUserIdAndDeviceId(userId, deviceId)
                .map(existing -> {
                    existing.heartbeat(platform);
                    return devices.save(existing);
                })
                .orElseGet(() -> devices.save(Device.register(userId, deviceId, platform)));
    }

    /**
     * M5.1 heartbeat: refresh the device's liveness ({@code online} + last-seen) and report whether the backend
     * wants a fresh full report — i.e. the client's current {@code stateHash} differs from the last one we stored
     * (or we have never stored one). Idempotent-upserts so a heartbeat before the first registration still lands.
     */
    @Transactional
    public boolean heartbeat(UUID userId, String deviceId, boolean online, String stateHash) {
        Device device = devices.findByUserIdAndDeviceId(userId, deviceId)
                .orElseGet(() -> Device.register(userId, deviceId, null));
        device.checkIn(online);
        boolean reportNeeded = !Objects.equals(stateHash, device.getStateHash());
        devices.save(device);
        return reportNeeded;
    }

    /**
     * M5.1 report: store the client's full backward-contract snapshot ({@code report}) + its {@code stateHash}, so
     * a subsequent heartbeat carrying the same hash no longer asks for a report. Idempotent-upserts.
     */
    @Transactional
    public Device applyReport(UUID userId, String deviceId, String report, String stateHash) {
        Device device = devices.findByUserIdAndDeviceId(userId, deviceId)
                .orElseGet(() -> Device.register(userId, deviceId, null));
        device.applyReport(report, stateHash);
        return devices.save(device);
    }

    @Transactional(readOnly = true)
    public List<Device> list(UUID userId) {
        return devices.findByUserIdOrderByLastSeenAtDesc(userId);
    }

    /** Per-user device counts for the admin dashboard (internal surface). */
    @Transactional(readOnly = true)
    public List<DeviceCount> counts() {
        return devices.countByUser();
    }

    @Transactional
    public boolean remove(UUID userId, String deviceId) {
        return devices.deleteByUserIdAndDeviceId(userId, deviceId) > 0;
    }
}
