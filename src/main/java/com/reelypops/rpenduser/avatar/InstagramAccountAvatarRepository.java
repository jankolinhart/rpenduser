package com.reelypops.rpenduser.avatar;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InstagramAccountAvatarRepository extends JpaRepository<InstagramAccountAvatar, UUID> {

    Optional<InstagramAccountAvatar> findByUserIdAndIgHandle(UUID userId, String igHandle);
}
