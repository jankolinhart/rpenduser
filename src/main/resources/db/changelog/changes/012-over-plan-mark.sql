--liquibase formatted sql

-- WHEN THIS ACCOUNT WENT OVER ITS PLAN, per pool (operator, 16/09/2026: "we give them a grace period of 7
-- days with daily reminders ... after 7 days we force them with an overlay to chose").
--
-- A grace period needs a start, and nothing anywhere recorded one. Being over a ceiling is an ordinary state
-- here — a downgrade never reaches into somebody's configuration and takes things away — so an account can
-- sit above a limit indefinitely and no row in this system says since when.
--
-- IT HAS TO BE THE CLOUD'S FACT. Counted on a client the seven days would restart on every reinstall, run
-- differently on two machines of the same account, and be settable by moving the system clock. One row here
-- is the same answer to every reader.
--
-- A STANDING MARK, NOT A HISTORY. One row per (user, pool): written the first time a reader observes that
-- pool over, and DELETED the moment it observes it inside again. Deliberately the same shape as
-- rpsupportgroup's sg_membership_tombstone, and for the same reason — "has this account been over since?" is
-- a question a row that either exists or does not cannot get wrong, while deriving it from an append-only
-- log of goings-over and comings-back means comparing two timestamps and being wrong when they tie.
--
-- The stamp is never rewritten while the mark stands. That is the whole point: a clock re-stamped on each
-- read would never reach day seven.
--
-- PER POOL, SEPARATELY (the operator's ruling): seats, Instagram accounts and group connections each get
-- their own clock, so the seat taken on Tuesday does not inherit the deadline of the group joined last week.

--changeset rpenduser:012
CREATE TABLE over_plan_mark (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL,
    pool       varchar(32) NOT NULL,
    since      timestamptz NOT NULL,
    CONSTRAINT uq_over_plan_mark_user_pool UNIQUE (user_id, pool)
);

CREATE INDEX idx_over_plan_mark_user ON over_plan_mark (user_id);

COMMENT ON TABLE over_plan_mark IS
    'The standing mark that an account is over one of its plan ceilings, and since when. Written when a '
    'reader first observes the pool over, deleted when it observes it inside again, and never re-stamped '
    'while it stands — a clock re-stamped on every read would never reach day seven.';
COMMENT ON COLUMN over_plan_mark.pool IS
    'Which ceiling: SEATS, IG_ACCOUNTS or GROUP_CONNECTIONS. The three are counted three different ways and '
    'run their own clocks.';
--rollback DROP TABLE over_plan_mark;
