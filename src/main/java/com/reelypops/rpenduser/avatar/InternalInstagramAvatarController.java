package com.reelypops.rpenduser.avatar;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * The internal surface for a customer's own Instagram handle pictures (key-authed, no end-user JWT). Only the
 * rpserver BFF reaches it, having proved the customer's token; the user id in the path is that trusted
 * subject and never anything a client chose.
 *
 * <p>The bytes arrive from the CLIENT, fetched through the customer's own residential Instagram session —
 * the same contribution path support-group avatars use. Nothing here reaches Instagram, and nothing here
 * ever may.
 */
@RestController
@RequestMapping("/enduser/v1/internal")
public class InternalInstagramAvatarController {

    private final InstagramAccountAvatarService avatars;

    public InternalInstagramAvatarController(InstagramAccountAvatarService avatars) {
        this.avatars = avatars;
    }

    /**
     * Contribute this handle's picture. {@code 204} when stored, {@code 400} when the bytes are not
     * something we will serve as a picture — see {@link InstagramAccountAvatarService} for what that means.
     */
    @PutMapping("/users/{userId}/instagram-accounts/{igHandle}/avatar")
    public ResponseEntity<Void> put(@PathVariable UUID userId,
                                    @PathVariable String igHandle,
                                    @RequestHeader(value = "Content-Type", required = false) String contentType,
                                    @RequestBody(required = false) byte[] image) {
        return avatars.put(userId, igHandle, image, contentType)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.badRequest().build();
    }

    /**
     * Serve it. {@code 404} when no client has contributed one — the ORDINARY case for a handle added a
     * moment ago, which every reader already draws as a monogram.
     *
     * <p>Cached privately and briefly. The picture changes about never, and the alternative is re-sending
     * tens of kilobytes per row on a screen that re-reads whenever anything about the plan changes. Private
     * because it belongs to one customer: a shared cache must never hold it.
     */
    @GetMapping("/users/{userId}/instagram-accounts/{igHandle}/avatar")
    public ResponseEntity<byte[]> get(@PathVariable UUID userId, @PathVariable String igHandle) {
        return avatars.get(userId, igHandle)
                .map(avatar -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(avatar.getContentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                        .body(avatar.getImage()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
