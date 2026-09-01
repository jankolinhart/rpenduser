--liquibase formatted sql

--changeset assistant:010-stop-order-event
-- AN APPEND-ONLY RECORD OF EVERY STOP ORDER, AND EVERY LIFTING OF ONE.
--
-- user_stop_order holds ONE MUTABLE ROW PER USER. Re-issuing overwrites it in place, and clear() — the
-- ENABLE button, the RECOVERY action — deletes it outright. So the act of putting a customer back to work
-- destroys the only evidence of what was done to them and by whom, and an escalation from DISABLE to KILL
-- leaves no trace that the gentler order was ever given.
--
-- That is the wrong way round. A stop is the most consequential thing an administrator can do to a paying
-- customer, and the record of it must outlive the decision that set it.
--
-- Nothing here is ever updated or deleted. The table only grows; age is the only thing that will ever
-- prune it, and that is a decision for a retention policy rather than for the code that writes it.
CREATE TABLE stop_order_event (
    id           UUID        PRIMARY KEY,
    order_id     UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    action       VARCHAR(32) NOT NULL,
    -- ISSUED | CLEARED. What happened to the order, not what the order says.
    event        VARCHAR(16) NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    -- The label the CALLER supplied. Not verified, and named so nobody reads it as though it were.
    claimed_by   VARCHAR(200),
    -- The address the request arrived from, as the service could best establish it.
    source_ip    VARCHAR(64)
);

CREATE INDEX idx_stop_order_event_user ON stop_order_event (user_id, occurred_at DESC);
CREATE INDEX idx_stop_order_event_time ON stop_order_event (occurred_at DESC);

--rollback DROP TABLE stop_order_event;
