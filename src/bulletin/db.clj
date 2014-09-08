(ns bulletin.db
  (:require [clojure.java.jdbc :as j]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [noir.util.crypt :as crypt]
            [bulletin.config :as config]))

;; Convert json db values to/front clojure maps and vectors
;; http://hiim.tv/clojure/2014/05/15/clojure-postgres-json/

;; Maps
(extend-protocol j/ISQLValue
  clojure.lang.IPersistentMap
  (sql-value [value]
    (doto (org.postgresql.util.PGobject.)
      (.setType "json")
      (.setValue (json/encode value)))))

(extend-protocol j/IResultSetReadColumn
  org.postgresql.util.PGobject
  (result-set-read-column [pgobj metadata idx]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (json/decode value true)
        value  ; Default
        ))))

;; Vectors
(defn value-to-json-pgobject [value]
  (doto (org.postgresql.util.PGobject.)
    (.setType "json")
    (.setValue (json/decode value))))

(extend-protocol j/ISQLValue
  clojure.lang.IPersistentMap
  (sql-value [value] (value-to-json-pgobject value))

  clojure.lang.IPersistentVector
  (sql-value [value] (value-to-json-pgobject value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def db-spec config/database-url)

(defn find-communities []
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT *
FROM communities
"])))

;; Returns {..., :forum {...}}
(defn find-post [post_id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT
  p.*,
  to_json(f.*) \"forum\"
FROM posts p
JOIN topics t ON p.topic_id = t.id
JOIN forums f ON t.forum_id = f.id
WHERE p.id = ?
" post_id] :result-set-fn first)))

(defn find-categories [community_id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT *
FROM categories
WHERE community_id = ?
" community_id])))

(defn find-community-staff [community_id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT
  r.*,
  u.username,
  to_json(u.*) \"user\"
FROM roles r
JOIN users u ON r.user_id = u.id
WHERE r.community_id = ?
" community_id])))

;; Returns [Forum]
(defn find-forums [category_ids]
  (j/with-db-connection [conn db-spec]
    (j/query conn [(str "
SELECT
 f.*,
 to_json(p.*) latest_post,
 to_json(t.*) latest_topic,
 to_json(u.*) latest_user
FROM forums f
LEFT OUTER JOIN posts p ON f.latest_post_id = p.id
LEFT OUTER JOIN topics t ON t.id = p.topic_id
LEFT OUTER JOIN users u ON u.id = p.user_id
WHERE f.category_id IN (" (str/join "," category_ids) ")
ORDER BY position
")])))

(defn update-post! [post-id text]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
UPDATE posts
SET text = ?, updated_at = NOW()
WHERE id = ?
RETURNING *
" text post-id] :result-set-fn first)))

(defn find-user [user_id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT
  u.*,
  to_json(array_agg(r.*)) \"roles\"
FROM users u
LEFT OUTER JOIN roles r ON u.id = r.user_id
WHERE id = ?
GROUP BY u.id
" user_id]
             ;; Convert :roles from [nil] to []
             :result-set-fn (fn [users]
                              (let [user (first users)]
                                (when user
                                  (update-in user
                                             [:roles]
                                             (partial remove nil?))))))))

;; Case-insensitive username lookup
(defn find-user-by-username [username]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT *
FROM users
WHERE LOWER(username) = LOWER(?)
" username] :result-set-fn first)))

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

(defn create-user! [{:keys [username email password]}]
  (let [digest (crypt/encrypt password)]
    (j/with-db-connection [conn db-spec]
      (j/query conn ["
INSERT INTO users (username, digest, email)
VALUES (?, ?, ?)
RETURNING *
" username digest email] :result-set-fn first))))

;; Returns String session_id
(defn create-session! [user_id]
  (let [session_id (java.util.UUID/randomUUID)
        session (j/with-db-connection [conn db-spec]
                  (j/query conn ["
INSERT INTO sessions (id, user_id)
VALUES (?, ?)
RETURNING *
" session_id user_id] :result-set-fn first))]
    (str (:id session))))

;; -- Keeping roleless version around in case I don't like it roles system
;; (defn find-user-by-session-id [session_id]
;;   (j/with-db-connection [conn db-spec]
;;     (j/query conn ["
;; SELECT u.*
;; FROM users u
;; WHERE u.id = (
;;   SELECT s.user_id
;;   FROM sessions s
;;   WHERE s.id = ?::uuid
;;     -- Sessions become invalid after 2 weeks
;;     AND created_at >= (NOW() - INTERVAL '14 days')
;;   )
;; " session_id] :result-set-fn first)))

(defn find-user-by-session-id [session_id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT
  u.*,
  to_json(array_agg(r.*)) \"roles\"
FROM users u
LEFT OUTER JOIN roles r ON u.id = r.user_id
WHERE u.id = (
  SELECT s.user_id
  FROM sessions s
  WHERE s.id = ?::uuid
    -- Sessions become invalid after 2 weeks
    AND created_at >= (NOW() - INTERVAL '14 days')
  )
GROUP BY u.id;
" session_id]
             ;; Since :roles will be `[nil]` if the user has no roles,
             ;; we need to (remove nil? roles) so that empty seq is returned
             :result-set-fn (fn [users]
                              (let [user (first users)]
                                (when user
                                  (update-in user
                                             [:roles]
                                             (partial remove nil?))))))))

; (find-user-by-session-id "ca394b06-b490-4c3e-a598-ce50fcf93bdc")

;; Returns {:title _, ..., :posts [Post]}
(defn create-topic! [{:keys [forum_id title description user_id text ip]}]
  (j/with-db-connection [conn db-spec]
    (let [topic (j/query conn ["INSERT INTO topics (forum_id, title, user_id)
                                VALUES (?, ?, ?)
                                RETURNING *;" forum_id title user_id]
                         :result-set-fn first)
          post (j/query conn [" INSERT INTO posts (topic_id, text, user_id, ip)
                                VALUES (?, ?, ?, ?::inet)
                                RETURNING *;" (:id topic) text user_id ip]
                        :result-set-fn first)]
      (assoc topic :posts [post]))))

(defn create-community! [{:keys [name slug] :as attrs}]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
INSERT INTO communities (title, slug)
VALUES (?, ?)
RETURNING *
" name slug]
:result-set-fn first)))

(defn delete-sessions! [user_id]
  (j/with-db-connection [conn db-spec]
    (j/execute! conn ["
DELETE FROM sessions
WHERE user_id = ?
" user_id])))

(defn find-community [community_id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT *
FROM communities
WHERE id = ?
" community_id]
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

(defn find-forum [id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT
  f.*,
  to_json(c.*) category
FROM forums f
JOIN categories c ON f.category_id = c.id
WHERE f.id = ?
" id] :result-set-fn first)))

(defn find-forum-topics [forum-id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT
  t.*,
  to_json(u.*) \"user\",
  to_json(p.*) \"latest_post\",
  to_json(u2.*) \"latest_user\"
FROM topics t
JOIN users u ON t.user_id = u.id
LEFT JOIN posts p ON p.id = t.latest_post_id
LEFT JOIN users u2 ON p.user_id = u2.id
WHERE t.forum_id = ?
ORDER BY t.latest_post_id DESC
" forum-id])))

(defn create-post! [{:keys [user_id topic_id text ip]}]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
INSERT INTO posts (user_id, topic_id, text, ip)
VALUES (?, ?, ?, ?::inet)
RETURNING *
" user_id topic_id text ip])))

(defn find-topic [topic-id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT
  t.*,
  to_json(f.*) forum,
  to_json(c.*) category
FROM topics t
JOIN forums f ON t.forum_id = f.id
JOIN categories c ON f.category_id = c.id
WHERE t.id = ?
" topic-id] :result-set-fn first)))

(defn find-topic-posts-paginated [topic-id current-user-id]
  (j/with-db-connection [conn db-spec]
    (j/query conn ["
SELECT
  p.*,
  to_json(u.*) \"user\",
  COUNT(likes.id) \"likes_count\",
  array_agg(likes.user_id::bigint) @> Array[?::bigint] \"has_liked\",
  array_agg(reports.user_id::bigint) @> Array[?::bigint] \"has_reported\"
FROM posts p
JOIN users u ON p.user_id = u.id
LEFT JOIN likes ON p.id = likes.post_id
LEFT JOIN reports ON p.id = reports.post_id
WHERE p.topic_id = ?::bigint
GROUP BY p.id, u.id
ORDER BY p.created_at ASC
LIMIT ?
OFFSET ?
" current-user-id
  current-user-id
  topic-id
  10000000  ; limit (TODO)
  0         ; offset (TODO)
  ])))

(defn reset-db! []
  (let [reset-sql (slurp (io/resource "migrations/0_reset_db.sql"))]
    (j/with-db-connection [conn db-spec]
      ;; Rebuild DB
      (j/execute! conn [reset-sql])


;; INSERT INTO communities (title, slug)
;; VALUES ('foo', 'foo');
;; INSERT INTO categories (title, description, community_id, position)
;; VALUES ('Test Category', 'Test description', 1, 0);
;; INSERT INTO forums (category_id, title, description, position)
;; VALUES (1, 'Test forum', 'Desc', 0);
;; INSERT INTO topics (forum_id, title, user_id)
;; VALUES (1, 'Test topic', 1);
;; INSERT INTO posts (topic_id, user_id, text)
;; VALUES (1, 1, 'Test post');
      ;; Comm 1

      ;; Seed users
      ;; User 1
      (create-user! {:username "gadmin",
                     :email "gadmin@example.com",
                     :password "secret"})
      ;; 2
      (create-user! {:username "cadmin1",
                     :email "cadmin1@example.com",
                     :password "secret"})
      ;; 3
      (create-user! {:username "cadmin2",
                     :email "cadmin2@example.com",
                     :password "secret"})
      ;; 4
      (create-user! {:username "smod1",
                     :email "smod1@example.com",
                     :password "secret"})
      ;; 5
      (create-user! {:username "smod2",
                     :email "smod2@example.com",
                     :password "secret"})
      ;; 6
      (create-user! {:username "mod1-f1",
                     :email "mod1-f1@example.com",
                     :password "secret"})
      ;; 7
      (create-user! {:username "mod1-f2",
                     :email "mod1-f2@example.com",
                     :password "secret"})
      ;; 8
      (create-user! {:username "member",
                     :email "member@example.com",
                     :password "secret"})
      (let [com1 (create-community! {:name "Community 1", :slug "community-1"})
            com2 (create-community! {:name "Community 2", :slug "community-2"})
            cat1 (create-category! {:community_id (:id com1)
                                    :title "Category 1"
                                    :description "Category 1 description"})
            cat2 (create-category! {:community_id (:id com2)
                                    :title "Category 2"
                                    :description "Category 2 description"})
            f1-1 (create-forum! {:category_id (:id cat1)
                               :title "Forum 1"
                               :description "Forum 1 description"})
            f1-2 (create-forum! {:category_id (:id cat1)
                               :title "Forum 2"
                               :description "Forum 2 description"})
            f2-1 (create-forum! {:category_id (:id cat2)
                               :title "Forum 2"
                               :description "Forum 2 description"})
            t1 (create-topic! {:forum_id (:id f1-1)
                               :title "Topic 1"
                               :user_id 1
                               :text "hello world"})
            t2 (create-topic! {:forum_id (:id f2-1)
                              :title "Topic 1"
                              :user_id 1
                               :text "hello world"})
            _ (create-topic! {:forum_id (:id f1-2)
                              :title "My Topic"
                              :user_id 1
                              :text "hello world"})
            ])

      (j/execute! conn ["
INSERT INTO roles (user_id, title)
VALUES (1, 'admin');
"])
      (j/execute! conn ["
INSERT INTO roles (user_id, title, community_id)
VALUES (2, 'admin', 1);
"])
      (j/execute! conn ["
INSERT INTO roles (user_id, title, community_id)
VALUES (3, 'admin', 2);
"])
      (j/execute! conn ["
INSERT INTO roles (user_id, title, community_id)
VALUES (4, 'supermod', 1);
"])
      (j/execute! conn ["
INSERT INTO roles (user_id, title, community_id)
VALUES (5, 'supermod', 2);
"])

      ;; user 6 is mod of comm1-f1 and comm1-f2
      (j/execute! conn ["
INSERT INTO roles (user_id, title, community_id, forum_id)
VALUES (6, 'mod', 1, 1);
"])
      (j/execute! conn ["
INSERT INTO roles (user_id, title, community_id, forum_id)
VALUES (6, 'mod', 1, 2);
"])
      (j/execute! conn ["
INSERT INTO roles (user_id, title, community_id, forum_id)
VALUES (7, 'mod', 1, 2);
"])
      ;; Seed db
      (j/execute! conn ["

-- Create global admin
INSERT INTO roles (user_id, title)
VALUES (1, 'admin');
"])
      (map :table_name (j/query conn ["
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_schema, table_name;
"]))
      )))

;; (reset-db!)
