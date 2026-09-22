package com.reelypops.rpenduser.scrape;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceScrapeProgressRepository extends JpaRepository<DeviceScrapeProgress, UUID> {

    Optional<DeviceScrapeProgress> findByDeviceUuidAndIgAccount(UUID deviceUuid, String igAccount);

    /** Every machine's state for one group — the console's per-group view. */
    List<DeviceScrapeProgress> findByIgAccount(String igAccount);
}
