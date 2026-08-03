package com.reelypops.rpenduser.release;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the singleton {@link ClientReleaseState} (keyed by {@link ClientReleaseState#SINGLETON_ID}). */
public interface ClientReleaseStateRepository extends JpaRepository<ClientReleaseState, Integer> {
}
