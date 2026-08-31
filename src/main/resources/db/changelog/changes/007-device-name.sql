--liquibase formatted sql

-- A MACHINE HAS A NAME (31/08/2026).
--
-- Every surface that identifies a machine to a person does it by OS string: the client's takeover modal says
-- another machine has your account and calls it "Windows", rpauth's seat refusal names the other machines the
-- same way, and the admin console is about to show "2 of 3 seats live" over indistinguishable SHA-256
-- fingerprints. With two Macs, or two Windows boxes, none of that can be acted on.
--
-- device_id stays the identity and platform stays the fact; this is the LABEL, chosen on the machine itself,
-- because naming is only unambiguous there — picking a fingerprint out of a list and guessing which laptop it
-- is is the problem being solved.
--
-- Carried on registration and on every heartbeat rather than by a rename endpoint of its own: the label
-- travels where platform already goes, so a rename is simply the next check-in and there is no second path to
-- fall out of sync.
--
-- NULLABLE, and it stays that way. A client that sends no name has no opinion, not an empty one, and the
-- console falls back to platform + first-seen exactly as it does today.

--changeset rpenduser:007
ALTER TABLE device ADD COLUMN device_name varchar(80);
COMMENT ON COLUMN device.device_name IS
    'What the user calls this machine, chosen on the machine and carried by registration + every heartbeat. '
    'The human label beside the opaque device_id, because platform cannot separate two machines on one OS '
    'version. NULL when the client has never sent one — callers fall back to platform.';
--rollback ALTER TABLE device DROP COLUMN device_name;
