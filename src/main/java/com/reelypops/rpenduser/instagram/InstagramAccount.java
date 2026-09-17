package com.reelypops.rpenduser.instagram;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * An Instagram account a customer has CLAIMED.
 *
 * <h2>Why it exists</h2>
 * Until now the cloud learned a handle only SIDEWAYS: when it joined a support group (a membership row in
 * rpsupportgroup) or when a machine reported it in focus. Neither is the fact. A customer could configure
 * any number of accounts and the cloud counted none of them, so {@code igaccounts.max} was enforced at
 * "joins its first group" rather than at "adds an account" — and the Seat Map could not say what a customer
 * actually had (operator, 16/09/2026: "any change to it, adding/removing needs to be registered in the cloud
 * and refused at the client if the limit does not allow it").
 *
 * <h2>Claimed, not observed</h2>
 * A row here is what the USER DID, admitted by rpserver against the ceiling, and removed by a deliberate
 * release. It is deliberately NOT written from a device report: rpsupportgroup settled the same question for
 * memberships and the rule is the same one — a report is what a client SAW, and absence in it is not a
 * removal. A machine that can see only some of a customer's configuration must never be able to delete the
 * rest.
 */
@Entity
@Table(name = "instagram_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstagramAccount {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Stored lower-cased by the service: comparing handles any other way is how one account becomes two. */
    @Column(name = "ig_handle", nullable = false, updatable = false)
    private String igHandle;

    /**
     * WHERE it was last claimed, and a hint rather than a key. An account is an ACCOUNT-level fact that
     * survives the machine; the Seat Map uses this only to draw it under the computer it lives on.
     */
    @Column(name = "device_id")
    private String deviceId;

    /**
     * When the customer first took this account on, and never rewritten. A re-claim of an account they
     * already hold is the same claim — the date it became theirs has not changed, and moving it would make
     * "since when" answer the wrong question on every screen that asks.
     */
    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    private InstagramAccount(UUID userId, String igHandle, String deviceId, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.igHandle = igHandle;
        this.deviceId = deviceId;
        this.claimedAt = now;
        this.lastSeenAt = now;
    }

    static InstagramAccount claim(UUID userId, String igHandle, String deviceId, Instant now) {
        return new InstagramAccount(userId, igHandle, deviceId, now);
    }

    /** Seen again, possibly from a different machine. The claim date stands; the rest is current. */
    void seen(String deviceId, Instant now) {
        if (deviceId != null && !deviceId.isBlank()) {
            this.deviceId = deviceId;
        }
        this.lastSeenAt = now;
    }
}
