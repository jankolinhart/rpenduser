package com.reelypops.rpenduser.avatar;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps one profile picture per (customer, Instagram handle).
 *
 * <h2>What it will not store</h2>
 * Bytes it cannot serve as a picture. A client that hands over an empty body, or something declaring itself
 * to be anything other than an image, is contributing a broken frame to every screen that draws this handle
 * — and a broken frame is worse than the monogram it would replace, which is designed to hold exactly that
 * space. Refused at the door rather than filtered at each reader: there is one door and there are many
 * readers.
 *
 * <p>There is a ceiling on the size for the same reason a ceiling exists on anything a client can push: this
 * is the only route in this service that accepts arbitrary bytes, and a profile picture that does not fit in
 * it is not a profile picture.
 */
@Service
public class InstagramAccountAvatarService {

    /**
     * An Instagram profile picture is a few tens of kilobytes at its largest. A quarter of a megabyte is
     * generous enough that no real one is refused and small enough that a client cannot fill a table with
     * one call.
     */
    static final int MAX_BYTES = 256 * 1024;

    /** What a browser will actually paint. A list, not a prefix check: "image/svg+xml" is a script. */
    static final Set<String> SERVABLE = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final InstagramAccountAvatarRepository avatars;

    public InstagramAccountAvatarService(InstagramAccountAvatarRepository avatars) {
        this.avatars = avatars;
    }

    /**
     * Store or replace this handle's picture.
     *
     * @return {@code true} when it was stored, {@code false} when the bytes are not a picture we will serve
     */
    @Transactional
    public boolean put(UUID userId, String igHandle, byte[] image, String contentType) {
        String handle = normalise(igHandle);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (handle.isEmpty() || image == null || image.length == 0 || image.length > MAX_BYTES
                || !SERVABLE.contains(type)) {
            return false;
        }
        Instant now = Instant.now();
        avatars.findByUserIdAndIgHandle(userId, handle).ifPresentOrElse(
                existing -> existing.update(image, type, now),
                () -> avatars.save(InstagramAccountAvatar.create(userId, handle, image, type, now)));
        return true;
    }

    /** This handle's picture, or empty when no client has contributed one — an ordinary answer, not a fault. */
    @Transactional(readOnly = true)
    public Optional<InstagramAccountAvatar> get(UUID userId, String igHandle) {
        return avatars.findByUserIdAndIgHandle(userId, normalise(igHandle));
    }

    /**
     * Handles are stored and compared lower-cased, with a leading '@' dropped. Comparing them any other way
     * is how one account becomes two — and two pictures for one handle means the map draws whichever it
     * happens to find.
     */
    private static String normalise(String igHandle) {
        if (igHandle == null) {
            return "";
        }
        String trimmed = igHandle.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
