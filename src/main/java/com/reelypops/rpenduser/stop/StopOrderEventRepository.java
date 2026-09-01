package com.reelypops.rpenduser.stop;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Append and read. NOTHING ELSE.
 *
 * <p>Deliberately {@link Repository} rather than {@code JpaRepository}: the latter inherits the whole
 * {@code delete*} family, and a history with a delete method on it is one somebody will eventually call —
 * most likely from a well-meaning cleanup. What cannot be reached cannot be reached by accident.
 */
public interface StopOrderEventRepository extends Repository<StopOrderEvent, UUID> {

    StopOrderEvent save(StopOrderEvent event);

    List<StopOrderEvent> findByUserIdOrderByOccurredAtDesc(UUID userId);

    List<StopOrderEvent> findAllByOrderByOccurredAtDesc();
}
