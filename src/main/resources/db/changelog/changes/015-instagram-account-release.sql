--liquibase formatted sql

-- WHAT A MACHINE MUST UNDO (operator, 17/09/2026: removing an excess Instagram account from the Seat Map).
--
-- A release deletes the claim, and a deleted row says nothing. So a customer who removed an account on the
-- website had no way to tell the computer it was set up on: the slot came back in the cloud and the account
-- carried on existing on the machine, which is the two halves disagreeing about one subscription.
--
-- TOMBSTONES, NEVER A DIFF. A client cannot work this out by comparing what it holds against the claims the
-- cloud lists, because a handle missing from that list might have been released and might equally be one the
-- cloud has never been told about — an account claimed while the cloud was unreachable, or one that predates
-- the claim entirely. ABSENT IS NOT RELEASED. This row records what the USER DID, which is the only thing
-- safe to act on when the act is a removal.
--
-- CURRENT STATE, NOT HISTORY. One row per (user, handle), written by a release and destroyed by a claim.
-- Re-adding an account the customer had removed must stop the machines undoing it — otherwise a re-add would
-- be quietly reversed by the next catch-up, which is the same class of bug the membership tombstone exists
-- to prevent, pointing the other way.
--
-- Unlike rpsupportgroup's sg_membership_tombstone this one guards NOTHING on the write path: nothing reports
-- accounts, so there is no observation that could resurrect a released claim. Its whole job is to be read by
-- the machine that has to catch up. Said plainly here because the two tables look alike and only one of them
-- is defending a write.

--changeset rpenduser:015
CREATE TABLE instagram_account_release (
    id          uuid PRIMARY KEY,
    user_id     uuid         NOT NULL,
    ig_handle   varchar(128) NOT NULL,
    released_at timestamptz  NOT NULL
);

-- One standing mark per handle per user: a release upserts it, a claim removes it. Lower-cased by the
-- service, exactly as the claim is — two spellings of one handle is a machine undoing the wrong account.
CREATE UNIQUE INDEX ux_instagram_account_release_user_handle ON instagram_account_release (user_id, ig_handle);
CREATE INDEX ix_instagram_account_release_user ON instagram_account_release (user_id);

COMMENT ON TABLE instagram_account_release IS
    'Accounts a customer has RELEASED and not re-claimed — what a machine must undo. A tombstone rather than '
    'a diff: absence from the claim list is not a release.';
--rollback DROP TABLE instagram_account_release;
