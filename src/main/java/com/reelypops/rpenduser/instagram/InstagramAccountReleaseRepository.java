package com.reelypops.rpenduser.instagram;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The accounts a customer has released and not re-claimed — what a machine must undo. */
public interface InstagramAccountReleaseRepository extends JpaRepository<InstagramAccountRelease, UUID> {

    Optional<InstagramAccountRelease> findByUserIdAndIgHandle(UUID userId, String igHandle);

    /** Oldest first, so a client acts on the longest-standing decision before the newest one. */
    List<InstagramAccountRelease> findByUserIdOrderByReleasedAtAsc(UUID userId);

    long deleteByUserIdAndIgHandle(UUID userId, String igHandle);
}
