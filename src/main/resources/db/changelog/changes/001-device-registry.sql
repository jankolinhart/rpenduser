--liquibase formatted sql

-- rpenduser device registry (D3): one row per (user, machine). device_id is the client's opaque, salted
-- hardware-fingerprint hash (never the raw MAC/UUID); platform is granular OS info (e.g. 'macOS 14.5'), which
-- is common/aggregate and not PII. Deliberately NO hostname (it can embed a real name). rppayment reads this
-- registry for seat enforcement.

--changeset rpenduser:001-device
CREATE TABLE device (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL,
    device_id     varchar(128) NOT NULL,
    platform      varchar(100),
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_user_device UNIQUE (user_id, device_id)
);
CREATE INDEX idx_device_user ON device (user_id);
--rollback DROP TABLE device;
