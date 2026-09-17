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
 * The standing mark that a customer has RELEASED an Instagram account and has not since re-claimed it.
 *
 * <h2>Why a deleted row is not enough</h2>
 * Releasing deletes the claim, and a deleted row says nothing. So a customer who removed an account from the
 * Seat Map freed the slot in the cloud while the account carried on existing on the computer it was set up
 * on — the two halves of one subscription disagreeing, with nothing anywhere to reconcile them.
 *
 * <h2>Tombstones, never a diff</h2>
 * A machine cannot work this out by comparing what it holds against the claims the cloud lists. A handle
 * missing from that list might have been released, and might equally be one the cloud was never told about —
 * an account claimed while the cloud was unreachable, or one that predates the claim entirely. <b>Absent is
 * not released.</b> This row is what the USER DID, which is the only thing safe to act on when the act is a
 * removal.
 *
 * <h2>Current state, not history</h2>
 * One row per {@code (userId, igHandle)}, written by a release and destroyed by a claim. Re-adding an account
 * they had removed must stop the machines undoing it, or the re-add would be quietly reversed by the next
 * catch-up — the same class of bug rpsupportgroup's membership tombstone prevents, pointing the other way.
 *
 * <h2>What it does NOT do, because the two tables look alike</h2>
 * {@code sg_membership_tombstone} also defends a WRITE path: device reports replay a client's observed
 * memberships, and without it a report would resurrect a membership its own user had released. Nothing
 * reports accounts — the claim is the only writer — so this row guards nothing and its whole job is to be
 * read by the machine that has to catch up. Said plainly so nobody later assumes a protection it does not
 * provide.
 */
@Entity
@Table(name = "instagram_account_release")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstagramAccountRelease {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Lower-cased, exactly as the claim is: two spellings of one handle is a machine undoing the wrong one. */
    @Column(name = "ig_handle", nullable = false, updatable = false)
    private String igHandle;

    @Column(name = "released_at", nullable = false)
    private Instant releasedAt;

    private InstagramAccountRelease(UUID userId, String igHandle, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.igHandle = igHandle;
        this.releasedAt = now;
    }

    static InstagramAccountRelease of(UUID userId, String igHandle, Instant now) {
        return new InstagramAccountRelease(userId, igHandle, now);
    }

    /**
     * Released AGAIN. The mark stands either way; what moves is when — a machine that has been away for a
     * month should be told this is a fresh decision rather than one it may already have acted on.
     */
    void releasedAt(Instant now) {
        this.releasedAt = now;
    }
}
