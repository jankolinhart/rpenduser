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

    /** Every device of this user claiming the handle — the candidate set the holder is chosen from. */
    List<Device> findByUserIdAndFocusedHandle(UUID userId, String focusedHandle);

    long deleteByUserIdAndDeviceId(UUID userId, String deviceId);

    /**
     * Device tally per user (one row per user that owns at least one device) for the admin dashboard, with
     * the LIVE count alongside the total.
     *
     * <p>A clean goodbye counts as not-live immediately, matching {@code presenceOf}: a machine that said it
     * was closing must not keep reading as running for the rest of its live window.
     *
     * <p>Grouped in the database rather than in Java because this runs for EVERY user on one dashboard load
     * — the reason {@code device(last_seen_at)} is indexed.
     *
     * @param liveSince the start of the live window; a device heard from at or after it is live
     */
    @Query("select new com.reelypops.rpenduser.device.DeviceCount(d.userId, count(d), "
            + "sum(case when d.lastSeenAt >= :liveSince and d.shutdownAt is null then 1L else 0L end)) "
            + "from Device d group by d.userId")
    List<DeviceCount> countByUser(java.time.Instant liveSince);
}
