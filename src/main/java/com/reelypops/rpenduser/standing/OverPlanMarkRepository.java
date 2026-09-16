package com.reelypops.rpenduser.standing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OverPlanMarkRepository extends JpaRepository<OverPlanMark, UUID> {

    List<OverPlanMark> findByUserId(UUID userId);
}
