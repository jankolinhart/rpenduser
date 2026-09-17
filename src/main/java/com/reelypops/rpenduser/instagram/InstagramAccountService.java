package com.reelypops.rpenduser.instagram;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Instagram accounts a customer has claimed — the count {@code igaccounts.max} is enforced against.
 *
 * <h2>Claiming is idempotent, and that is load-bearing</h2>
 * The client claims before it adds, and a claim it already holds must succeed rather than fail. Two reasons,
 * and the second is the one that bites: a retry after a lost response must not refuse an account the
 * customer already has; and the CEILING is checked by rpserver before this is called, so a claim that
 * "failed" for already existing would make a caller believe it had been refused for room — which is the one
 * sentence it must never show wrongly.
 *
 * <p>Re-claiming refreshes where and when the account was last seen. It never moves {@link
 * InstagramAccount#getClaimedAt()}: the date it became theirs has not changed, and moving it would make
 * "since when" answer the wrong question on every screen that asks.
 *
 * <h2>Releasing is a deliberate act, and the only way a row goes</h2>
 * Nothing here is written or removed from a device report. rpsupportgroup settled the same question for
 * memberships and the rule is the same: a report is what a client SAW, and absence in it is not a removal. A
 * machine that can see only part of a customer's configuration must never delete the rest.
 */
@Service
public class InstagramAccountService {

    private final InstagramAccountRepository accounts;

    public InstagramAccountService(InstagramAccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Take on an account, or confirm one already held.
     *
     * @return {@code true} when this claim CREATED the row — so a caller can tell taking one on from seeing
     *         one again, which is the difference between spending a slot and not
     */
    @Transactional
    public boolean claim(UUID userId, String igHandle, String deviceId) {
        String handle = normalise(igHandle);
        if (handle.isEmpty()) {
            return false;
        }
        Instant now = Instant.now();
        InstagramAccount held = accounts.findByUserIdAndIgHandle(userId, handle).orElse(null);
        if (held != null) {
            held.seen(deviceId, now);
            return false;
        }
        accounts.save(InstagramAccount.claim(userId, handle, deviceId, now));
        return true;
    }

    /**
     * Give one up.
     *
     * @return {@code true} when a row actually went. Releasing one that is not held is not an error — it is
     *         what a retry looks like, and what a client tidying up after itself looks like.
     */
    @Transactional
    public boolean release(UUID userId, String igHandle) {
        String handle = normalise(igHandle);
        return !handle.isEmpty() && accounts.deleteByUserIdAndIgHandle(userId, handle) > 0L;
    }

    /** Everything this customer has claimed, oldest first — a stable order, so two readers agree. */
    @Transactional(readOnly = true)
    public List<InstagramAccount> heldBy(UUID userId) {
        return accounts.findByUserIdOrderByClaimedAtAsc(userId);
    }

    /**
     * Handles are stored and compared lower-cased, with a leading '@' dropped. Instagram treats @Jean_Marc
     * and @jean_marc as one account; storing them apart would let a customer hold the same account twice and
     * be refused by a ceiling they are nowhere near.
     */
    private static String normalise(String igHandle) {
        if (igHandle == null) {
            return "";
        }
        String trimmed = igHandle.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
