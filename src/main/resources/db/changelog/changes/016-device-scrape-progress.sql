--liquibase formatted sql

-- WHAT A MACHINE IS DOING ABOUT ONE GROUP RIGHT NOW (22/09/2026: the console's deep-scrape console).
--
-- An operator can now start a deep scrape on a named machine. Pressing a button and then learning nothing is
-- not a feature: a deep scrape walks a whole tagged grid and can run for many minutes, so the console has to
-- be able to say whether it started, how far it has got, and whether it failed — otherwise the only honest
-- thing the screen could show after "start" is nothing at all, and the operator would press it again.
--
-- WHY HERE. The client already counts what it has scanned, but it counted it only for its own window. The
-- cloud's transport (rpserver) is a stateless BFF with no database, and production runs more than one task
-- with no stickiness, so a progress report posted to it would be remembered by whichever task received it
-- and be invisible to the console about half the time — a bar that moves, stops, and moves again, describing
-- nothing. This registry already owns what a machine IS (its presence, the handle it is working, the stop
-- orders it has obeyed), so what it is doing about a group belongs beside that and nowhere else.
--
-- ONE ROW PER (MACHINE, GROUP), AND IT IS CURRENT STATE, NOT HISTORY. A machine can be asked to scrape more
-- than one group, so a column on `device` would have to pick one. A finished scrape leaves its DONE row in
-- place deliberately — "412 posts, finished four minutes ago" is exactly what the operator wants to read
-- after it ends — and the next scrape of that group on that machine overwrites it. Nobody is keeping a log
-- here; the corpus snapshots are the record of what was actually collected.
--
-- NO ROW MEANS NOTHING IS KNOWN, WHICH IS NOT THE SAME AS IDLE. A machine that has never scraped this group,
-- a machine on a build too old to report, and a machine whose report never arrived all produce the same
-- absence. The console must therefore say "no scrape reported" rather than "idle", because claiming a
-- machine is idle is a claim about the machine, and this table cannot make one.

--changeset rpenduser:016-device-scrape-progress
CREATE TABLE device_scrape_progress (
    id             uuid PRIMARY KEY,
    device_uuid    uuid        NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    ig_account     varchar(255) NOT NULL,
    state          varchar(16) NOT NULL,
    scanned_count  integer,
    started_at     timestamptz,
    finished_at    timestamptz,
    message        varchar(500),
    updated_at     timestamptz NOT NULL,
    CONSTRAINT device_scrape_progress_unique UNIQUE (device_uuid, ig_account)
);
CREATE INDEX device_scrape_progress_ig_account_idx ON device_scrape_progress (ig_account);
COMMENT ON TABLE device_scrape_progress IS
    'What one machine is doing about one support group''s deep scrape. Current state, not history: the next '
    'scrape of that group on that machine overwrites the row. Absence means nothing is known, not idle.';
COMMENT ON COLUMN device_scrape_progress.state IS
    'REQUESTED (accepted for delivery), DELIVERED (the machine has it), RUNNING, DONE, STOPPED (an operator '
    'asked it to stop — its own ending, not a failure and not a completion), FAILED, or REFUSED (the machine '
    'declined — typically it does not run this group). There is no IDLE: that is the absent row.';
COMMENT ON COLUMN device_scrape_progress.scanned_count IS
    'Posts seen so far. There is no total to divide it by — a deep scrape walks until the grid ends — so this '
    'is a count that rises, never a percentage.';
COMMENT ON COLUMN device_scrape_progress.message IS
    'Why, in words an operator can act on, when state is FAILED or REFUSED. Null otherwise.';
COMMENT ON COLUMN device_scrape_progress.device_uuid IS
    'The device ROW, not the fingerprint string: the same machine can be registered to two customers, and a '
    'scrape belongs to the one that asked for it. Cascades, because a removed machine is doing nothing.';
--rollback DROP TABLE device_scrape_progress;
