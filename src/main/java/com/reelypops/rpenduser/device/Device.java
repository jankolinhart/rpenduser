package com.reelypops.rpenduser.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A device registered to an end user (D3): one row per (user, machine). {@code deviceId} is the client's
 * opaque, salted hardware-fingerprint hash — never the raw MAC/UUID — and {@code platform} is granular OS
 * info (e.g. {@code macOS 14.5}); there is deliberately no hostname, which can embed a real name. rppayment
 * reads this registry for seat enforcement.
 */
@Entity
@Table(name = "device")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Device {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "platform")
    private String platform;

    @CreationTimestamp
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    private Device(UUID userId, String deviceId, String platform) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.deviceId = deviceId;
        this.platform = platform;
        this.lastSeenAt = Instant.now();
    }

    /** Register a newly-seen device for a user. */
    public static Device register(UUID userId, String deviceId, String platform) {
        return new Device(userId, deviceId, platform);
    }

    /** Record that an already-registered device checked in again — refresh its platform + last-seen. */
    public void heartbeat(String platform) {
        this.platform = platform;
        this.lastSeenAt = Instant.now();
    }
}
