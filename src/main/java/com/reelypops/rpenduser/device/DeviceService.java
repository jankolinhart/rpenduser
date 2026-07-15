package com.reelypops.rpenduser.device;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
