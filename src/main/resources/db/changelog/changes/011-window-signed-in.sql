--liquibase formatted sql

-- WHETHER SOMEBODY IS SIGNED IN AT THE MACHINE (11/09/2026 — rpdocu desktop-works-for-the-last-account.md).
--
-- A computer now works for the last account signed in on it whether or not anybody is signed in to its window:
-- the sign-in only locks the window. So "holds a seat" stopped meaning "somebody is signed in", and the console
-- could no longer answer the question it was asked for — is a person actually at that machine, signed in?
-- The client says so on every heartbeat; this keeps it.
--
-- NULL is "this client does not say" — a build from before the field. It is never read as false.

--changeset rpenduser:011
ALTER TABLE device ADD COLUMN window_signed_in boolean;
COMMENT ON COLUMN device.window_signed_in IS
    'Whether the app''s window was signed in at this machine''s last heartbeat. NULL: the client does not '
    'report it (a build from before 11/09/2026).';
--rollback ALTER TABLE device DROP COLUMN window_signed_in;
