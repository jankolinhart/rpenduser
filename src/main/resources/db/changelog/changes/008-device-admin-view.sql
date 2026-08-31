--liquibase formatted sql

-- WHAT THE OPERATOR NEEDS TO SEE ABOUT A MACHINE (31/08/2026).
--
-- The console can show a user's devices, but only the fingerprint, the OS and two timestamps — so the one
-- question support actually gets asked ("is their app running, and which version?") cannot be answered from
-- it. Two additions close that.
--
-- app_version: the client already SENDS this on every heartbeat, and it was read to decide whether to offer
-- an update and then thrown away. Storing it costs nothing and turns "which build is that user on?" from a
-- question into a column.
--
-- The index on last_seen_at is for the USER LIST, not the drill-down. The list shows every user at once with
-- a "2 of 3 live" tally beside each, which is a grouped count over the whole table with a recency predicate
-- — a full scan on every dashboard load without it. The per-user drill-down does not need it (it is already
-- keyed by user); this exists solely for the aggregate.

--changeset rpenduser:008
ALTER TABLE device ADD COLUMN app_version varchar(40);
COMMENT ON COLUMN device.app_version IS
    'The client build this machine last reported on a heartbeat, or NULL. Already sent on every beat; now '
    'kept, so the console can answer "which version is that user running?".';

CREATE INDEX idx_device_last_seen_at ON device (last_seen_at);
--rollback DROP INDEX idx_device_last_seen_at;
--rollback ALTER TABLE device DROP COLUMN app_version;
