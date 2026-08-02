--liquibase formatted sql

-- rpenduser device telemetry (M5.1 backward contract): extend the device registry with the client's IG-session
-- liveness (online), the fingerprint of the last stored report (state_hash — a heartbeat carrying a different
-- hash triggers a fresh report), the last full report snapshot (report, opaque JSON — ignore-unknown/additive),
-- and when it was stored (last_report_at). Feeds the P6 duty scheduler + the #2 drift self-heal.

--changeset rpenduser:002-device-telemetry
ALTER TABLE device ADD COLUMN online boolean;
ALTER TABLE device ADD COLUMN state_hash varchar(64);
ALTER TABLE device ADD COLUMN report text;
ALTER TABLE device ADD COLUMN last_report_at timestamptz;
--rollback ALTER TABLE device DROP COLUMN online, DROP COLUMN state_hash, DROP COLUMN report, DROP COLUMN last_report_at;
