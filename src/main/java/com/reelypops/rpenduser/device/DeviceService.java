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
        device.setFocusedHandle(focusedHandleOf(report));
        return devices.save(device);
    }

    /**
     * The handle {@code igAccounts[]} marks {@code inFocus}, or {@code null}.
     *
     * <p>TOTAL, and deliberately so: a report is stored opaquely and additively, so this must survive a body
     * that is malformed, differently shaped, or from a future client. Anything it cannot read means the
     * device claims NOTHING — never an exception, because a projection failure must not fail the report that
     * carries it, and never a blank, because a blank would collide with every other silent device.
     *
     * <p>More than one {@code inFocus} cannot happen on a correct client (the focus is a singleton row) and is
     * not worth an error here: the first is taken, which is the same answer the scheduler would act on.
     */
    static String focusedHandleOf(String report) {
        if (report == null || report.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode accounts =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(report).path("igAccounts");
            for (com.fasterxml.jackson.databind.JsonNode account : accounts) {
                if (account.path("inFocus").asBoolean(false)) {
                    String handle = account.path("handle").asText(null);
                    return handle == null || handle.isBlank() ? null : handle;
                }
            }
        } catch (Exception e) {
            // A report we cannot parse is a report that claims nothing. It is still STORED — the blob is the
            // source of truth and a later reader may understand it — so nothing is lost by declining to guess.
            return null;
        }
        return null;
    }

    /**
     * The user's other devices already claiming {@code handle}, newest-seen first. Empty when the handle is
     * absent, so a caller cannot turn "nothing reported" into a conflict.
     */
    @Transactional(readOnly = true)
    public List<Device> claimingSameHandle(UUID userId, String deviceId, String handle) {
        if (handle == null || handle.isBlank()) {
            return List.of();
        }
        return devices.findByUserIdAndFocusedHandleAndDeviceIdNot(
                userId, handle.trim().toLowerCase(java.util.Locale.ROOT), deviceId);
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
