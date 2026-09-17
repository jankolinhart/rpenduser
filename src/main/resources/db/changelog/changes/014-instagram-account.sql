--liquibase formatted sql

-- AN INSTAGRAM ACCOUNT IS A THING THE CLOUD KNOWS ABOUT (operator, 16/09/2026: "any change to it,
-- adding/removing needs to be registered in the cloud and refused at the client if the limit does not allow
-- it").
--
-- Until now the cloud learned a handle only SIDEWAYS: when it joined a support group (a membership row in
-- rpsupportgroup) or when a machine reported it in focus (device.focused_handle). Neither is the fact. A
-- customer could configure any number of accounts and the cloud would count none of them, so `igaccounts.max`
-- was enforced at "joins its first group" rather than at "adds an account" — and the Seat Map could not say
-- what a customer actually had.
--
-- CLAIMED, NOT OBSERVED. A row here is what the USER DID, admitted by rpserver against the ceiling, and it is
-- removed by a deliberate release. It is deliberately NOT written from a device report: rpsupportgroup
-- settled the same question for memberships, and the rule is the same one — a report is what a client SAW,
-- and absence in it is not a removal. A machine that can see only some of a customer's configuration must
-- never be able to delete the rest.
--
-- user_id is a uuid, like every other table here. It was written varchar(64) while this migration sat on a
-- branch, before the neighbouring tables settled; Hibernate's schema validation caught it the first time the
-- entity met the real column, which is exactly what that validation is for.
--
-- device_id is WHERE it was last claimed, and it is a hint rather than a key: an account is an ACCOUNT-level
-- fact that survives the machine, and the Seat Map uses this only to draw it under the computer it lives on.

--changeset rpenduser:014
CREATE TABLE instagram_account (
    id           uuid PRIMARY KEY,
    user_id      uuid         NOT NULL,
    ig_handle    varchar(128) NOT NULL,
    device_id    varchar(128),
    claimed_at   timestamptz  NOT NULL,
    last_seen_at timestamptz
);

-- The natural key: one row per handle per user. Handles are stored lower-cased by the service, because
-- comparing them any other way is how one account becomes two — and two is what gets somebody refused.
CREATE UNIQUE INDEX ux_instagram_account_user_handle ON instagram_account (user_id, ig_handle);
CREATE INDEX ix_instagram_account_user ON instagram_account (user_id);

COMMENT ON TABLE instagram_account IS
    'Instagram accounts a customer has CLAIMED — what the user did, admitted against igaccounts.max, and '
    'removed only by a deliberate release. Never written from a device report: absence in an observation is '
    'not a removal.';
COMMENT ON COLUMN instagram_account.device_id IS
    'Where this account was last claimed. A HINT for drawing it under its computer on the Seat Map, never a '
    'key: the account is account-level and survives the machine.';
--rollback DROP TABLE instagram_account;
