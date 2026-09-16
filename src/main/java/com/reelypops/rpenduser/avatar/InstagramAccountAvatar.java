package com.reelypops.rpenduser.avatar;

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
 * The profile picture of one of a customer's OWN Instagram handles.
 *
 * <h2>Where the bytes come from</h2>
 * The CLIENT, through the customer's own residential Instagram session — exactly as support-group avatars
 * are contributed. The cloud never touches Instagram, and this table introduces no server-side capture.
 *
 * <h2>Why here rather than beside the group avatars</h2>
 * rpsupportgroup already stores an avatar per Instagram account, and an account's picture is the same
 * picture whoever is looking, so one shared store is arguably the truer model. It is the wrong one here for
 * a single reason: that table has no user scoping ON PURPOSE — a group is shared and so is its picture — and
 * a customer's handles are not shared. Rows here are per user by construction.
 *
 * <p>The cost is accepted: a handle that is also a support group is stored twice. A few kilobytes against a
 * scoping property the other table cannot gain without changing what it means.
 */
@Entity
@Table(name = "instagram_account_avatar")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstagramAccountAvatar {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Stored lower-cased by the service: comparing handles any other way is how one account becomes two. */
    @Column(name = "ig_handle", nullable = false, updatable = false)
    private String igHandle;

    @Column(name = "image", nullable = false)
    private byte[] image;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private InstagramAccountAvatar(UUID userId, String igHandle, byte[] image, String contentType, Instant at) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.igHandle = igHandle;
        this.image = image;
        this.contentType = contentType;
        this.updatedAt = at;
    }

    static InstagramAccountAvatar create(UUID userId, String igHandle, byte[] image, String contentType,
                                         Instant at) {
        return new InstagramAccountAvatar(userId, igHandle, image, contentType, at);
    }

    /** Replace the stored picture — a client re-contributes a fresher one whenever Instagram's changes. */
    void update(byte[] image, String contentType, Instant at) {
        this.image = image;
        this.contentType = contentType;
        this.updatedAt = at;
    }
}
