--liquibase formatted sql

-- THE FOCUSED HANDLE, PROJECTED OUT OF THE OPAQUE REPORT (28/08/2026).
--
-- Two devices can put the SAME Instagram account in focus. That buys the customer nothing — the same
-- account's work is simply done twice — and it DOUBLES the like-rate on that account, defeating the client's
-- anti-bot pacing exactly two-fold. It is the pattern behind the 24-hour Instagram block of 28/07/2026, and
-- the last thing standing between the seat add-on and an honest description of it ("a seat lets one more of
-- your Instagram accounts work at the same time").
--
-- The fact was ALREADY on the wire and nobody read it: the client's backward-contract report carries
-- `igAccounts[].{handle,inFocus}`, and `inFocus` sits inside the report's stateHash, so a focus switch flips
-- the fingerprint and the next heartbeat pulls a fresh report within ~60s. It was stored opaquely in
-- `device.report` and parsed only to forward memberships.
--
-- This projects it into a column so it can be QUERIED — which is all a claim needs to be.

--changeset rpenduser:004
ALTER TABLE device ADD COLUMN focused_handle varchar(64);
COMMENT ON COLUMN device.focused_handle IS
    'The Instagram handle this device last reported as in focus (igAccounts[].inFocus), normalised lower-case. '
    'NULL when the device has never reported one or reports none in focus. Projected from device.report so a '
    'second device claiming the same handle can be detected; the report itself stays the source of truth.';
CREATE INDEX ix_device_user_focused_handle ON device (user_id, focused_handle);
--rollback DROP INDEX ix_device_user_focused_handle;
--rollback ALTER TABLE device DROP COLUMN focused_handle;
