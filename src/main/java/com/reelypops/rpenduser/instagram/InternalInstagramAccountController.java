package com.reelypops.rpenduser.instagram;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The internal Instagram-account surface (key-authed, no end-user JWT). Only the rpserver BFF reaches it,
 * having proved the customer's token and checked {@code igaccounts.max}; the user id in the path is that
 * trusted subject and never anything a client chose.
 *
 * <p><strong>The ceiling is not checked here.</strong> It is rpserver's, because rpserver is what reads the
 * entitlement — this service stores what it is told and counts what it holds. Two places deciding one
 * ceiling is how they come to disagree, and the one that refuses a customer must be the one that can explain
 * why.
 */
@RestController
@RequestMapping("/enduser/v1/internal/users/{userId}/instagram-accounts")
public class InternalInstagramAccountController {

    private final InstagramAccountService accounts;

    public InternalInstagramAccountController(InstagramAccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * Take an account on, or confirm one already held. {@code 201} when the row was created and {@code 200}
     * when it already existed — a caller can then tell spending a slot from seeing one again, and a retry
     * after a lost response never reads as a refusal.
     */
    @PostMapping
    public ResponseEntity<Void> claim(@PathVariable UUID userId, @RequestBody InstagramAccountClaim claim) {
        if (claim == null || claim.igHandle() == null || claim.igHandle().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        boolean created = accounts.claim(userId, claim.igHandle(), claim.deviceId());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    /**
     * Give one up. Always {@code 204}: releasing an account that is not held is what a retry looks like, and
     * what a client tidying up after itself looks like — neither is a fault, and a 404 would make a caller
     * treat a finished job as an unfinished one.
     */
    @DeleteMapping("/{igHandle}")
    public ResponseEntity<Void> release(@PathVariable UUID userId, @PathVariable String igHandle) {
        accounts.release(userId, igHandle);
        return ResponseEntity.noContent().build();
    }

    /** Everything this customer holds. The count the ceiling is enforced against, and the Seat Map's truth. */
    @GetMapping
    public List<InstagramAccountView> held(@PathVariable UUID userId) {
        return accounts.heldBy(userId).stream().map(InstagramAccountView::of).toList();
    }
}
