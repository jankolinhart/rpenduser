package com.reelypops.rpenduser.instagram;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstagramAccountRepository extends JpaRepository<InstagramAccount, UUID> {

    List<InstagramAccount> findByUserIdOrderByClaimedAtAsc(UUID userId);

    Optional<InstagramAccount> findByUserIdAndIgHandle(UUID userId, String igHandle);

    long deleteByUserIdAndIgHandle(UUID userId, String igHandle);
}
