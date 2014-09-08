DROP TABLE IF EXISTS communities CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS categories CASCADE;
DROP TABLE IF EXISTS topics CASCADE;
DROP TABLE IF EXISTS forums CASCADE;
DROP TABLE IF EXISTS posts CASCADE;
DROP TABLE IF EXISTS likes CASCADE;
DROP TABLE IF EXISTS reports CASCADE;
DROP TABLE IF EXISTS sessions CASCADE;
DROP TABLE IF EXISTS roles CASCADE;

CREATE TABLE communities (
  id          bigserial                  PRIMARY KEY,
  title       text                       NOT NULL,
  slug        text                       NOT NULL  UNIQUE,
  created_at  timestamp with time zone   NOT NULL  DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
  id            bigserial  PRIMARY KEY,
  digest        text       NOT NULL,
  -- Is there a way to ensure usernames and emails are unique at
  -- community_id scope in db layer?
  username      text       NOT NULL  UNIQUE,
  email         text       NOT NULL  UNIQUE,
  -- Global admins (staff) don't belong to a community
  community_id  bigint  NULL  REFERENCES communities(id)  ON DELETE CASCADE,
  created_at    timestamp with time zone   NOT NULL  DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE categories (
  id           bigserial    PRIMARY KEY,
  title        text      NOT NULL,
  description  text      NULL,
  position     bigint       NOT NULL,
  community_id bigint       NOT NULL  REFERENCES communities(id)
);

CREATE TABLE forums (
  id                bigserial      PRIMARY KEY,
  category_id       bigint         REFERENCES categories(id),
  title             text        NOT NULL,
  description       text        NULL,
  position          bigint         NOT NULL,
  topics_count      bigint         NOT NULL  DEFAULT 0,
  posts_count       bigint         NOT NULL  DEFAULT 0
);

CREATE TABLE sessions (
  id            uuid            PRIMARY KEY,
  user_id       int             REFERENCES users(id),
  created_at    timestamp with time zone       NOT NULL  DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE topics (
  id                bigserial   PRIMARY KEY,
  forum_id          bigint      NOT NULL
                                REFERENCES forums(id)
                                ON DELETE CASCADE,
  user_id           bigint      NOT NULL
                                REFERENCES users(id)
                                ON DELETE CASCADE,
  title             text        NOT NULL,
  created_at        timestamp with time zone   NOT NULL  DEFAULT CURRENT_TIMESTAMP,
  posts_count       bigint         NOT NULL  DEFAULT 0,
  is_locked         boolean     NOT NULL  DEFAULT false,
  is_hidden         boolean     NOT NULL  DEFAULT false,
  is_sticky         boolean     NOT NULL  DEFAULT false
);

CREATE TABLE posts (
  id               bigserial           PRIMARY KEY,
  topic_id         bigint              NOT NULL  REFERENCES topics(id),
  user_id          bigint              NOT NULL  REFERENCES users(id),
  text             text             NOT NULL,
  created_at       timestamp with time zone  NOT NULL  DEFAULT CURRENT_TIMESTAMP,
  updated_at       timestamp with time zone  NULL,
  ip               inet             NULL
);

CREATE TABLE reports (
  id           serial           PRIMARY KEY,
  -- The creator of the report
  user_id      int              NOT NULL  REFERENCES users(id),
  post_id      int              NOT NULL  REFERENCES posts(id),
  message      text             NOT NULL,
  created_at   timestamp with time zone       NOT NULL  DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE likes (
  id           bigserial           PRIMARY KEY,
  user_id      bigint              NOT NULL  REFERENCES users(id),
  post_id      bigint              NOT NULL  REFERENCES posts(id),
  -- So we can show who the trendsetters are
  created_at   timestamp with time zone       NOT NULL  DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
  user_id      bigint NOT NULL  REFERENCES users(id)  ON DELETE CASCADE,
  title        text   NOT NULL,
  -- If null, it's a global role
  community_id bigint NULL  REFERENCES communities(id)  ON DELETE CASCADE,
  forum_id     bigint NULL  REFERENCES forums(id)  ON DELETE CASCADE,
  -- created_at is used to enforce seniority. a less-senior admin cannot demote
  -- a more-senior admin.
  created_at   timestamp with time zone       NOT NULL  DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE forums
ADD COLUMN latest_post_id int NULL  REFERENCES posts(id);

ALTER TABLE topics
ADD COLUMN latest_post_id int NULL  REFERENCES posts(id);

------------------------------------------------------------

-- Update forum.topics_count when a topic is inserted/deleted

-- http://www.postgresql.org/docs/9.1/static/sql-start-transaction.html
-- http://www.postgresql.org/docs/8.0/static/plpgsql-trigger.html
-- http://www.postgresql.org/docs/9.1/static/sql-createtrigger.html
CREATE OR REPLACE FUNCTION update_forum_topics_count()
RETURNS trigger AS $update_forum_topics_count$
    BEGIN
        IF (TG_OP = 'DELETE') THEN
            UPDATE forums
            SET topics_count = topics_count - 1
            WHERE id = OLD.forum_id;
        ELSIF (TG_OP = 'INSERT') THEN
            UPDATE forums
            SET topics_count = topics_count + 1
            WHERE id = NEW.forum_id;
        END IF;
        RETURN NULL; -- result is ignored since this is an AFTER trigger
    END;
$update_forum_topics_count$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS topic_created ON topics;
CREATE TRIGGER topic_created
    AFTER INSERT OR DELETE ON topics
    FOR EACH ROW
    EXECUTE PROCEDURE update_forum_topics_count();

------------------------------------------------------------
-- Update forum.posts_count when a post is inserted/deleted

CREATE OR REPLACE FUNCTION update_forum_posts_count()
RETURNS trigger AS $update_forum_posts_count$
    BEGIN
        IF (TG_OP = 'DELETE') THEN
            UPDATE forums
            SET posts_count = posts_count - 1
            WHERE id = (
              SELECT forum_id
              FROM topics
              WHERE topics.id = OLD.topic_id
            );
        ELSIF (TG_OP = 'INSERT') THEN
            UPDATE forums
            SET posts_count = posts_count + 1
            WHERE id = (
              SELECT forum_id
              FROM topics
              WHERE topics.id = NEW.topic_id
            );
        END IF;
        RETURN NULL; -- result is ignored since this is an AFTER trigger
    END;
$update_forum_posts_count$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS post_created1 ON posts;
CREATE TRIGGER post_created1
    AFTER INSERT OR DELETE ON posts
    FOR EACH ROW
    EXECUTE PROCEDURE update_forum_posts_count();

------------------------------------------------------------
-- Update topic.posts_count when a post is inserted/deleted

CREATE OR REPLACE FUNCTION update_topic_posts_count()
RETURNS trigger AS $update_topic_posts_count$
    BEGIN
        IF (TG_OP = 'DELETE') THEN
            UPDATE topics
            SET posts_count = posts_count - 1
            WHERE id = OLD.topic_id;
        ELSIF (TG_OP = 'INSERT') THEN
            UPDATE topics
            SET posts_count = posts_count + 1
            WHERE id = NEW.topic_id;
        END IF;
        RETURN NULL; -- result is ignored since this is an AFTER trigger
    END;
$update_topic_posts_count$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS post_created2 ON posts;
CREATE TRIGGER post_created2
    AFTER INSERT OR DELETE ON posts
    FOR EACH ROW
    EXECUTE PROCEDURE update_topic_posts_count();

------------------------------------------------------------
-- Update forum.latest_post_id when a post is inserted/deleted

CREATE OR REPLACE FUNCTION update_forum_latest_post_id()
RETURNS trigger AS $update_forum_latest_post_id$
    BEGIN
        UPDATE forums
        SET latest_post_id = NEW.id
        WHERE id = (
          SELECT forum_id
          FROM topics
          WHERE topics.id = NEW.topic_id
        );
        RETURN NULL; -- result is ignored since this is an AFTER trigger
    END;
$update_forum_latest_post_id$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS post_created3 ON posts;
CREATE TRIGGER post_created3
    AFTER INSERT ON posts  -- Only on insert
    FOR EACH ROW
    EXECUTE PROCEDURE update_forum_latest_post_id();

------------------------------------------------------------

-- Update topic.latest_post_id when a post is inserted/deleted

CREATE OR REPLACE FUNCTION update_topic_latest_post_id()
RETURNS trigger AS $update_topic_latest_post_id$
    BEGIN
        UPDATE topics
        SET latest_post_id = NEW.id
        WHERE id = NEW.topic_id;
        RETURN NULL; -- result is ignored since this is an AFTER trigger
    END;
$update_topic_latest_post_id$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS post_created4 ON posts;
CREATE TRIGGER post_created4
    AFTER INSERT ON posts  -- Only on insert
    FOR EACH ROW
    EXECUTE PROCEDURE update_topic_latest_post_id();

------------------------------------------------------------
