--liquibase formatted sql

-- WHO HOLDS THE HANDLE, not merely who claims it (28/08/2026).
--
-- 004 records what each device says it is working. That is enough to DETECT a collision and not enough to
-- resolve one: with two live devices reporting the same handle, nothing says which of them should stop.
--
-- The operator's rule (28/08/2026): THE INCUMBENT HOLDS, and the newcomer is told which machine has it. A
-- second machine must never be able to interrupt a liking round already in flight.
--
-- So the holder is the LIVE claimer with the EARLIEST claim, and this column is that ordering. It is stamped
-- only when the handle CHANGES — not on every report — or the stamp would keep moving and "earliest" would
-- mean nothing.
--
-- It also makes takeover self-healing without a second table. Clearing the incumbent's claim hands the handle
-- to the challenger; when the old holder re-reports it is stamped afresh, so its claim is now the LATER one
-- and it correctly loses the tie-break to the machine that took over.

--changeset rpenduser:005
ALTER TABLE device ADD COLUMN focused_handle_at timestamptz;
COMMENT ON COLUMN device.focused_handle_at IS
    'When this device STARTED claiming its current focused_handle — stamped only when the handle changes, so '
    'it orders claims rather than tracking reports. The holder of a contested handle is the live claimer with '
    'the earliest stamp (the incumbent). NULL whenever focused_handle is NULL.';
--rollback ALTER TABLE device DROP COLUMN focused_handle_at;
