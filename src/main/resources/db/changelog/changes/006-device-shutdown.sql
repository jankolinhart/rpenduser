--liquibase formatted sql

-- A CLEAN GOODBYE, so "is this client running" stops being a guess for the ordinary case (31/08/2026).
--
-- Until now silence was the only exit signal, and it is read after five minutes (CLAIM_TTL) to release a
-- contested handle. That is fine for handle contention and useless for telling an operator which of a user's
-- machines is running right now: a laptop closed thirty seconds ago is indistinguishable from one mid-run.
--
-- The client now says goodbye on a CLEAN shutdown. This column records that it did.
--
-- IT IS A HINT, NOT THE TRUTH, and the distinction is the whole design. A crash, a power cut or a dead
-- network sends no goodbye, so liveness is still DERIVED from last_seen_at; the goodbye only makes the
-- ordinary case immediate instead of up to five minutes late. Treating it as authoritative would let the
-- console show a crashed machine as running for ever — which is the failure this exists to remove.
--
-- Cleared on the next check-in, so a device that comes back is simply back.

--changeset rpenduser:006
ALTER TABLE device ADD COLUMN shutdown_at timestamptz;
COMMENT ON COLUMN device.shutdown_at IS
    'When this client last reported a CLEAN shutdown, or NULL. A hint that brings presence forward in the '
    'ordinary case — never the source of truth, because a crash sends no goodbye. Cleared on the next '
    'check-in. Liveness is still derived from last_seen_at.';
--rollback ALTER TABLE device DROP COLUMN shutdown_at;
