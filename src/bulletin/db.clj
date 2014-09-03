(ns bulletin.db
  (:require [clojure.java.jdbc :as j]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bulletin.config :as config]))

(def db-spec config/database-url)

(defn find-communities []
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT *
FROM communities
"])))

(defn find-categories [community_id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT *
FROM categories
WHERE community_id = ?
" community_id])))

;; Returns [Forum]
(defn find-forums [category_ids]
  (j/with-db-connection [conn db-spec]
    (j/query conn [(str "
SELECT *
FROM forums f
WHERE f.category_id IN (" (str/join "," category_ids) ")
")])))

(defn create-category! [{:keys [community_id title description position]}]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
INSERT INTO categories (community_id, title, description, position)
VALUES (?, ?, ?, ?)
RETURNING *
" community_id title description (or position 0)]
:result-set-fn first)))

(defn create-forum! [{:keys [category_id title description position]}]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
INSERT INTO forums (category_id, title, description, position)
VALUES (?, ?, ?, ?)
RETURNING *
" category_id title description (or position 0)]
:result-set-fn first)))

(defn create-topic! [{:keys [forum_id title description user_id]}]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
INSERT INTO topics (forum_id, title, user_id)
VALUES (?, ?, ?)
RETURNING *
" forum_id title user_id]
:result-set-fn first)))

(defn create-community! [{:keys [name slug] :as attrs}]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
INSERT INTO communities (title, slug)
VALUES (?, ?)
RETURNING *
" name slug]
:result-set-fn first)))

;; String -> Community | nil
(defn find-community-by-slug [slug]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT *
FROM communities
WHERE slug = ?
" slug]
 :result-set-fn first)))

(defn reset-db! []
  (j/with-db-connection [conn db-spec]
    (j/execute! conn ["
DROP TABLE IF EXISTS communities CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS categories CASCADE;
DROP TABLE IF EXISTS topics CASCADE;
DROP TABLE IF EXISTS forums CASCADE;
DROP TABLE IF EXISTS posts CASCADE;
"])
    (j/execute! conn ["
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
  username      text       NOT NULL,
  email         text       NOT NULL,
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

INSERT INTO users (digest, username, email)
VALUES ('secret', 'danneu', 'danneu@example.com');

INSERT INTO communities (title, slug)
VALUES ('foo', 'foo');
INSERT INTO categories (title, description, community_id, position)
VALUES ('Test Category', 'Test description', 1, 0);
INSERT INTO forums (category_id, title, description, position)
VALUES (1, 'Test forum', 'Desc', 0)
"])
    (j/query conn ["
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_schema, table_name;
"])
))

(reset-db!)
