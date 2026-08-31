--liquibase formatted sql

-- AN ADMIN DECISION THAT CAN REACH A RUNNING MACHINE (31/08/2026).
--
-- Until now nothing an operator did in the console could stop work on a customer's own computer. Automation
-- is local by design (D16 — it must survive the cloud being unreachable), and the only lever we had was to
-- stop the customer signing in again, which does nothing to a machine already running.
--
-- This is the lever. rpadminserver writes an order here; the 60-second device heartbeat carries it to every
-- machine of that user; the machine acts and says so.
--
-- ONE ROW PER USER, NOT PER DEVICE. The decision is about an ACCOUNT — "stop this customer" — so a machine
-- that registers after the order was given is covered by it too, which is what an operator means. Per-device
-- would have to be re-issued for a laptop that came back tomorrow.
--
-- ABSENCE IS "CARRY ON", AND THAT IS THE WHOLE SAFETY PROPERTY. No row means no instruction. A cloud that is
-- unreachable, a query that fails, a service that is down — all of them produce the same nothing, and nothing
-- never stops a customer working. The alternative, storing "allowed" and inferring a stop from its absence,
-- would turn every outage into a fleet-wide halt.

--changeset rpenduser:009-stop-order
CREATE TABLE user_stop_order (
    user_id     uuid PRIMARY KEY,
    order_id    uuid        NOT NULL,
    action      varchar(32) NOT NULL,
    ordered_at  timestamptz NOT NULL,
    ordered_by  varchar(120)
);
COMMENT ON TABLE user_stop_order IS
    'An administrator has told this user''s machines to stop. One row per user; absence means carry on. '
    'Served on the 60s device heartbeat and cleared when the account is enabled or restored.';
COMMENT ON COLUMN user_stop_order.order_id IS
    'Identifies THIS order, so a device can say which one it obeyed. A re-issued order gets a new id and is '
    'therefore obeyed again, even by a device that acknowledged the previous one.';
COMMENT ON COLUMN user_stop_order.action IS 'DISABLE (finish what is running, start nothing) or KILL (stop now).';
--rollback DROP TABLE user_stop_order;

--changeset rpenduser:009-device-stop-ack
-- WHICH MACHINES HAVE ACTUALLY OBEYED. An operator pressing stop needs to know it landed — and on a machine
-- that was closed at the time, that it has NOT yet. Without this the console could only say "ordered".
ALTER TABLE device ADD COLUMN stop_acked_order_id uuid;
ALTER TABLE device ADD COLUMN stop_acked_at timestamptz;
COMMENT ON COLUMN device.stop_acked_order_id IS
    'The stop order this device last confirmed it had carried out. Compared against the live order to tell '
    '"stopped" from "stop pending".';
--rollback ALTER TABLE device DROP COLUMN stop_acked_at;
--rollback ALTER TABLE device DROP COLUMN stop_acked_order_id;
