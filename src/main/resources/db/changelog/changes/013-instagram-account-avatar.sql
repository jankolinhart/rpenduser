--liquibase formatted sql

-- A CUSTOMER'S OWN INSTAGRAM HANDLE HAS A FACE (operator, 16/09/2026: "all avatars are missing"; and then,
-- on being told the picture never leaves the machine: "we should find a way to transfer it to the cloud").
--
-- The desktop has had these pictures all along. It fetches each handle's profile picture through the
-- customer's own residential Instagram session and keeps it on disk, which is why the desktop's Seat Map can
-- draw every row and the website's cannot: nothing has ever sent one here.
--
-- THE CLOUD STILL NEVER TOUCHES INSTAGRAM. This table is fed exactly the way support-group avatars are fed —
-- the CLIENT fetches the bytes through its own exit and PUTs them. No server-side capture is introduced, and
-- none may be.
--
-- WHY HERE AND NOT BESIDE THE GROUP AVATARS. rpsupportgroup already stores an avatar per Instagram account,
-- and an account's profile picture is the same picture whoever is looking — so one shared store is arguably
-- the truer model. It is the wrong one here for a single reason: that table has no user scoping ON PURPOSE,
-- because a group is shared and so is its picture. A customer's own handles are not shared, and a row there
-- would be readable by anyone who guessed the handle. Rows here are per user by construction.
--
-- The cost is accepted and worth naming: a handle that is ALSO a support group is stored twice. That is a few
-- kilobytes against a scoping property that cannot be added to the other table without changing what it means.
--
-- Its own table rather than a column on `instagram_account` (the parked claim work), for the same reason
-- rpsupportgroup gives: a read of the configuration must never drag image bytes along with it.

--changeset rpenduser:013
CREATE TABLE instagram_account_avatar (
    id           uuid PRIMARY KEY,
    user_id      uuid         NOT NULL,
    ig_handle    varchar(128) NOT NULL,
    image        bytea        NOT NULL,
    content_type text         NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT uq_instagram_account_avatar_user_handle UNIQUE (user_id, ig_handle)
);

CREATE INDEX idx_instagram_account_avatar_user ON instagram_account_avatar (user_id);

COMMENT ON TABLE instagram_account_avatar IS
    'The profile picture of one of a customer''s OWN Instagram handles, contributed by their client through '
    'its residential Instagram session. Scoped per user: unlike a support group''s avatar, a handle''s '
    'picture is not shared.';
--rollback DROP TABLE instagram_account_avatar;
