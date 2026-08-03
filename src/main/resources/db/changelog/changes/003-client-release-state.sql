--liquibase formatted sql

-- M5.3c producer + admin gate (client-update-announcement-design.md): the per-stage client-release state — a SINGLETON
-- row (one rpenduser instance serves one stage/channel). published_version = the pipeline-verified "downloadable" fact
-- (ingested from the release pointer in a follow-up slice); announced_version + the curated blurb (announcement_urgency
-- + announcement_highlights, newline-joined user-facing bullets) is what the admin GATE promotes and what clients are
-- compared against (announced == published at announce time). The human gate (gate_enabled) is admin-toggleable on
-- DEV/TEST and forced on for PROD (enforced in ClientReleaseService).

--changeset rpenduser:003-client-release-state
CREATE TABLE client_release_state (
    id                       integer      PRIMARY KEY,
    published_version        varchar(128),
    published_at             timestamptz,
    announced_version        varchar(128),
    announced_at             timestamptz,
    announcement_urgency     varchar(16),
    announcement_highlights  text,
    gate_enabled             boolean      NOT NULL DEFAULT true
);
--rollback DROP TABLE client_release_state;
