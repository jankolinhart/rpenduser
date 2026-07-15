package com.reelypops.rpenduser.device;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByUserIdOrderByLastSeenAtDesc(UUID userId);

    Optional<Device> findByUserIdAndDeviceId(UUID userId, String deviceId);

    long deleteByUserIdAndDeviceId(UUID userId, String deviceId);
}
