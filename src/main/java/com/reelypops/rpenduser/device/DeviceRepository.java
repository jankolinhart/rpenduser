package com.reelypops.rpenduser.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByUserIdOrderByLastSeenAtDesc(UUID userId);

    Optional<Device> findByUserIdAndDeviceId(UUID userId, String deviceId);

    /**
     * The user's OTHER devices claiming this same handle — the conflict, expressed as a query.
     *
     * <p>{@code deviceId} is excluded so a device never conflicts with itself, and a NULL handle can never
     * match: absence is absence, so two devices that have simply never reported must not read as fighting
     * over nothing. Callers pass a normalised handle; {@link Device#setFocusedHandle} is what guarantees the
     * stored side is normalised too.
     */
    List<Device> findByUserIdAndFocusedHandleAndDeviceIdNot(UUID userId, String focusedHandle, String deviceId);

    long deleteByUserIdAndDeviceId(UUID userId, String deviceId);

    /** Device tally per user (one row per user that owns at least one device) for the admin dashboard. */
    @Query("select new com.reelypops.rpenduser.device.DeviceCount(d.userId, count(d)) "
            + "from Device d group by d.userId")
    List<DeviceCount> countByUser();
}
