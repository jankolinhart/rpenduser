package com.reelypops.rpenduser.stop;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserStopOrderRepository extends JpaRepository<UserStopOrder, UUID> {
}
