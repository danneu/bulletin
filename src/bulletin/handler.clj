(ns bulletin.handler
  (:require [compojure.core :refer [defroutes routes GET POST PUT DELETE]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file-info :refer [wrap-file-info]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [hiccup.middleware :refer [wrap-base-url]]
            [hiccup.util :refer [escape-html]]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [clojure.string :as str]
            [cheshire.core :as json]
            [bulletin.routes.home :refer [home-routes]]
            [prone.middleware :as prone]
            [bulletin.config :as config]
            [ring.util.response :refer [redirect]]
            [clojure.pprint :refer [pprint]]
            [org.httpkit.server :refer [run-server]]
            ;; To be extracted
            [pandect.core :as pandect]
            [noir.util.crypt :as crypt]
            [bulletin.cancan :as can]
            [selmer.parser :as p]
            [selmer.filters :as filters]
            [bulletin.db :as db])
  (:gen-class))

;; java.sql.Timestamp -> UTCString
;; My custom selmer filter for turning java.sql.Timestamp into UTC strings
;; since `str` drops the timezone data thus timeago thinks it's local time.
(defn timestamp [timestamp]
  (let [literal (pr-str timestamp)]
    (subs literal 7 (dec (count literal)))))

(filters/add-filter! :timestamp timestamp)

;; TODO: Extract somewhere or implement via selmer filter
(defn ->hex-color [s]
  (->> (pandect/md5 s)
       (take 6)
       (str/join "")
       (str "#")))

;; TODO: Move these to their own ns that I can refer when I need *current-community*
(def ^:dynamic *current-community* nil)

(defn extract-subdomain [request]
  (let [url (:server-name request)]
    (first (clojure.string/split url #"\."))))

(defn wrap-current-community
  "Loads current community from subdomain (unless subdomain is www)"
  [handler]
  (fn [request]
    (println "Subdomain:" (extract-subdomain request))
    (let [slug (extract-subdomain request)]
      (binding [*current-community* (when-not (= "www" slug)
                                      (db/find-community-by-slug slug))]
        (println "Current community:" *current-community*)
        (handler request)))))

;; TODO: Move these to their own ns that I can refer when I need *current-user*
(def ^:dynamic *current-user* nil)
(defn wrap-current-user
  "Loads current user from {:session_id _} in their session if it matches a
   row in the sessions table. Guests can be differentiated from logged-in users
   because guests do not have :id."
  [handler]
  (fn [request]
    (println "\n=====\nSESSION:" (-> request :session))
    (let [session_id (-> request :session :session_id)]
      (println "Session ID:" session_id)
      (binding [*current-user* (when session_id
                                 (db/find-user-by-session-id session_id))]
        (println "Current user:" *current-user*)
        (handler request)))))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: init and destroy aren't run in uberjar
(defn init []
  (println "bulletin is starting")
  (println config/database-url)
  ;; Don't cache Selmer templates
  ;; TODO: Only do this in dev
  (p/cache-off!))

;; TODO: init and destroy aren't run in uberjar
(defn destroy []
  (println "bulletin is shutting down"))

(defroutes app-routes
  ;;
  ;; Reset DB
  ;;
  (GET "/reset-db" []
    (if (can/can? *current-user* :reset-db {})
      (do (db/reset-db!)
          (-> (redirect (str "//www." config/app-domain))
              (assoc-in [:flash :message] ["success" "Successfully reset database"])))
      {:status 403, :body "Can't let you reset the db"}))
  ;;
  ;; Registration Page
  ;;
  (GET "/register" []
    (p/render-file "bulletin/views/community/register.html"
                   {:current-community *current-community*
                    :config config/config}))
  ;;
  ;; Edit user page
  ;;
  ;; TODO: Allow admin to attach mods to forums
  (GET "/users/:user-id/edit" [user-id :as req]
    (let [user-id (Integer/parseInt user-id)
          user (db/find-user user-id)]
      (p/render-file "bulletin/views/community/edit_user.html"
                     {:current-community *current-community*
                      :current-user *current-user*
                      ;; TODO: Somehow indicate global admins
                      :role (or (first (can/get-comm-roles user *current-community*))
                                "member")
                      :user user
                      :req req
                      :config config/config})))
  ;;
  ;; Show community staff
  ;;
  (GET "/staff" []
    (let [staff (db/find-community-staff (:id *current-community*))
          comm-admins (filter #(= "admin" (:title %)) staff)
          smods (filter #(= "supermod" (:title %)) staff)
          mods (->> (filter #(= "mod" (:title %)) staff)
                   (map :user)
                   (into #{}))]
      (p/render-file "bulletin/views/community/show_staff.html"
                     {:current-community *current-community*
                      :current-user *current-user*
                      :config config/config
                      ;; roles
                      :comm-admins comm-admins
                      :smods smods
                      :mods mods})))
  ;;
  ;; Show user
  ;;
  (GET "/users/:user-id" [user-id :as req]
    (let [user-id (Integer/parseInt user-id)
          user (db/find-user user-id)]
      (p/render-file "bulletin/views/community/show_user.html"
                     {:current-community *current-community*
                      :current-user *current-user*
                      ;; TODO: Somehow indicate global admins
                      :role (or (first (can/get-comm-roles user *current-community*))
                                "member")
                      :user user
                      :req req
                      :config config/config})))
  ;;
  ;; Create user
  ;;
  (POST "/users" req
    (let [username (-> req :params :user :username)
          password1 (-> req :params :user :password1)
          password2 (-> req :params :user :password2)
          email (-> req :params :user :email)]
      (let [[err user] (cond
                        (not= password1 password2) ["Passwords do not match"]
                        (str/blank? username) ["Username is required"]
                        :else [nil (db/create-user! {:username username
                                                     :password password1
                                                     :email email})])]
        (if err
          (pr-str err)
          ;; Log the new user in
          (let [session_id (db/create-session! (:id user))
                response (-> (redirect "/")
                             (assoc :session {:session_id session_id}))]
            (println response)
            response)))))
  ;;
  ;; Logout User
  ;;
  (DELETE "/sessions" _
    (when *current-user*
      (db/delete-sessions! (:id *current-user*)))
    (-> (redirect "/")
        (assoc :session nil)
        (assoc-in [:flash :message] ["success" "Successfully logged out"])))
  ;;
  ;; New category page
  ;;
  (GET "/categories/new" req
    (if (can/cannot? *current-user*
                     :create-category
                     {:community *current-community*})
      (-> (redirect "/")
          (assoc-in [:flash :message] ["danger" "You can't create categories"]))
      (p/render-file "bulletin/views/community/new_category.html"
                     {:current-user *current-user*
                      :current-community *current-community*
                      :config config/config})))
  ;;
  ;; New forum page
  ;;
  (GET "/forums/new" req
    (if (can/cannot? *current-user*
                     :create-forum
                     {:community *current-community*})
      (-> (redirect "/")
          (assoc-in [:flash :message] ["danger" "You can't create forums"]))
      (let [categories (db/find-categories (:id *current-community*))]
        (p/render-file "bulletin/views/community/new_forum.html"
                       {:current-user *current-user*
                        :current-community *current-community*
                        :categories categories
                        :config config/config}))))
  ;;
  ;; Create forum
  ;;
  (POST "/forums" req
    (if (can/cannot? *current-user*
                     :create-forum
                     {:community *current-community*})
      (-> (redirect "/")
          (assoc-in [:flash :message] ["danger" "You can't create forums"]))
      (let [title (-> req :params :forum :title)
            description (-> req :params :forum :description)
            ;; FIXME: Fails if client sends non-integer string
            ;; category_id will be -1 if user didn't selected a category
            category_id (when-let [category_id (-> req :params :forum :category_id)]
                          (Integer/parseInt category_id))]
        ;; TODO: Flesh out validation
        ;; TODO: Allow user to change position
        ;; TODO: Add authorization
        (let [[err forum] (cond
                           (= -1 category_id) ["Choose a category"]
                           :else [nil (db/create-forum!
                                       {:category_id category_id
                                        :title title
                                        :description description})]
                           )]
          (if err
            (pr-str err)
            (redirect "/"))))))
  ;;
  ;; Create category
  ;;
  (POST "/categories" req
    (if (can/cannot? *current-user*
                     :create-category
                     {:community *current-community*})
      (-> (redirect "/")
          (assoc-in [:flash :message] ["danger" "You can't create categories"]))
      (let [title (-> req :params :category :title)
            description (-> req :params :category :description)]
        ;; TODO: Allow user to change position
        ;; TODO: Add authorization
        ;; TODO: Flesh out validation
        (let [[err community] (cond
                               :else [nil (db/create-category!
                                           {:community_id (:id *current-community*)
                                            :title title
                                            :description description})])]
          (if err
            (pr-str err)
            (redirect "/"))))))
  ;;
  ;; Login User
  ;;
  (POST "/sessions" req
    (let [username (-> req :params :creds :username)
          password (-> req :params :creds :password)
          user (db/find-user-by-username username)]
      (if-not user
        "User with that username not found"
        (if-not (crypt/compare password (:digest user))
          "Invalid password"
          ;; Log the user in
          (let [session_id (db/create-session! (:id user))]
            (-> (redirect "/")
                (assoc :session {:session_id session_id})
                (assoc-in [:flash :message] ["success" "Successfully logged in"])))))))
  ;;
  ;; Create post
  ;;
  (POST "/forums/:forum-id/topics/:topic-id/posts" [forum-id topic-id :as req]
    ;; Params: :post-text
    (let [forum-id (Integer/parseInt forum-id)
          forum (db/find-forum forum-id)]
      (if (can/cannot? *current-user*
                       :create-post
                       {:community *current-community*
                        :forum forum})
        (-> (redirect "/")
            (assoc-in [:flash :message] ["danger" "You can't post here"]))
        (let [post-text (get-in req [:params :post-text])
              topic-id (Integer/parseInt topic-id)
              ip (:remote-addr req)
              _ (println ip)
              post (db/create-post! {:user_id (:id *current-user*)
                                     :topic_id topic-id
                                     :text post-text
                                     :ip ip})]
          (-> (redirect (str "/forums/" forum-id "/topics/" topic-id))
              (assoc-in [:flash :message] ["success" "Successfully created post"]))))))
  ;;
  ;; Show forum
  ;;
  (GET "/forums/:forum-id" [forum-id]
    (let [forum-id (Integer/parseInt forum-id)
          forum (db/find-forum forum-id)]
      (if (can/cannot? *current-user*
                       :read-forum
                       {:community *current-community*
                        :forum forum})
        (-> (redirect "/")
            (assoc-in [:flash :message] ["danger" "You can't read this forum"]))
        (let [topics (db/find-forum-topics forum-id)
              can-create-topic? (can/can?
                                 *current-user*
                                 :create-topic
                                 {:community *current-community*})]
          (p/render-file "bulletin/views/community/show_forum.html"
                         {:current-community *current-community*
                          :current-user (assoc *current-user*
                                          :can-create-topic?
                                          can-create-topic?)
                          :forum forum
                          :topics topics
                          :config config/config})))))
  ;;
  ;; Create topic
  ;;
  (POST "/forums/:forum-id/topics" [forum-id :as req]
    (let [forum_id (Integer/parseInt forum-id)
          forum (db/find-forum forum_id)]
      (if (can/cannot? *current-user*
                       :create-topic
                       {:community *current-community*
                        :forum forum})
        (-> (redirect "/")
            (assoc-in [:flash :message] ["danger" "You can't create a topic here"]))
        (let [title (-> req :params :topic :title)
              text (-> req :params :topic :text)
              ip (:remote-addr req)]
          ;; TODO: Add validation
          (let [[err topic] (cond
                             (str/blank? title) ["Title is required"]
                             (str/blank? text) ["Post is required"]
                             :else [nil (db/create-topic!
                                         {:forum_id forum_id
                                          :title title
                                          :text text
                                          :user_id (:id *current-user*)
                                          :ip ip})])]
            (if err
              (pr-str err)
              (-> (redirect (str "/forums/" forum-id "/topics/" (:id topic))))))))))
  ;;
  ;; Get raw post via AJAX
  ;;
  (GET "/api/posts/:post-id" [post-id]
    (let [post-id (Integer/parseInt post-id)
          post (db/find-post post-id)]
      (if (can/cannot? *current-user*
                       :read-post
                       {:community *current-community*
                        :forum (:forum post)
                        :post post})
        {:status 403, :body "Can't let you read this post"}
        (:text post))))
  ;;
  ;; Update post via AJAX
  ;;
  ;; Returns a post
  (PUT "/api/posts/:post-id" [post-id :as req]
    ;; TODO: Add authorization
    (let [post-id (Integer/parseInt post-id)
          new-text (-> req :params :text)
          post (db/find-post post-id)]
      (if (can/cannot? *current-user*
                       :update-post
                       {:community *current-community*
                        :forum (:forum post)
                        :post post})
        {:status 403, :body "Can't let you update this post"}
        ;; Don't consider the post edited if nothing was updated
        (let [response (if (= new-text (:text post))
                         post
                         (let [updated-post (db/update-post! post-id new-text)]
                           updated-post))]
          (json/encode response)))))
  ;;
  ;; Show topic
  ;;
  (GET "/forums/:forum-id/topics/:topic-id" [forum-id topic-id :as req]
    (let [forum-id (Integer/parseInt forum-id)
          topic-id (Integer/parseInt topic-id)
          topic (db/find-topic topic-id)]
      (if (can/cannot? *current-user*
                       :read-topic
                       {:community *current-community*
                        :forum (:forum topic)})
        (-> (redirect "/")
            (assoc-in [:flash :message] ["danger" "You can't read this topic"]))
        (let [posts (for [post (db/find-topic-posts-paginated topic-id 1)
                          :let [can-update? (can/can?
                                             *current-user*
                                             :update-post
                                             {:community *current-community*
                                              :post post})]]
                      (-> post
                          (assoc :can-update? can-update?)
                          (assoc-in [:user :hex-color]
                                    (->hex-color (:username (:user post))))))
              can-create-post? (can/can? *current-user*
                                         :create-post
                                         {:community *current-community*
                                          :topic topic})]
          (p/render-file "bulletin/views/community/show_topic.html"
                         {:current-community *current-community*
                          :current-user (-> *current-user*
                                            (assoc :can-create-post?
                                              can-create-post?))
                          :topic topic
                          :posts posts
                          :req req
                          :config config/config})))))
  ;;
  ;; Show post (html - use /api/post via ajax)
  ;;
  ;; This permalink will redirect to the post within the topic
  ;; to the correct paginated topic page according to user authorization.
  ;; For ex: since some users can see deleted posts, pagination isn't the
  ;; same for all users.
  (GET "/posts/:post-id" [post-id]
    (let [post-id (Integer/parseInt post-id)
          ;post (db/find-post post-id)
          ]
      "TODO"))
  ;;
  ;; Homepage
  ;;
  (GET "/" req
    (if *current-community*
      ;; Community homepage
      (let [categories (db/find-categories (:id *current-community*))
            ;; The following code is here simply to assoc a :forums collection
            ;; into each category in `categories`
            category-ids (map :id categories)
            forums (db/find-forums category-ids)
            ;; This is a map of {CategoryID -> [Forums]}
            grouped-forums (group-by :category_id forums)
            categories (for [category categories
                             :let [category-id (:id category)]]
                         ;; Default to empty collection instead of nil
                         (assoc category :forums (get grouped-forums category-id [])))
            can-create-category? (can/can? *current-user*
                                           :create-category
                                           {:community *current-community*})
            can-create-forum? (can/can? *current-user*
                                           :create-forum
                                           {:community *current-community*})
            ]
        (p/render-file "bulletin/views/community/homepage.html"
                       {:current-community *current-community*
                        :current-user (-> *current-user*
                                          (assoc :can-create-category?
                                            can-create-category?)
                                          (assoc :can-create-forum?
                                            can-create-forum?))
                        :categories categories
                        :req req
                        :config config/config}))
      ;; www homepage
      (let [communities (db/find-communities)]
        (p/render-file "bulletin/views/homepage.html"
                       {:communities communities
                        :current-user *current-user*
                        :config config/config}))))
  ;;
  ;; Create community
  ;;
  (POST "/communities" req
    (if (can/cannot? *current-user* :create-community {})
      (-> (redirect "/")
          (assoc-in [:flash :message] ["danger" "You can't create communities"]))
      (let [params (select-keys (:params req) [:name :slug])
            ;; TODO: Validate params
            community (db/create-community! {:name (:name params)
                                             :slug (:slug params)})
            category (db/create-category! {:community_id (:id community)
                                           :title "Off-Topic"
                                           :description "You can edit this category in the options"})
            forum (db/create-forum! {:category_id (:id category)
                                     :title "Test Forum"
                                     :description "This is an example forum"})
            topic (db/create-topic! {:forum_id (:id forum)
                                     :title "Test Topic"
                                     :user_id 1
                                     :text "Test Post"})]
        (-> (redirect (str "//" (:slug community) "." config/app-domain))
            (assoc-in [:flash :message] ["success" "Successfully create community"])))))
  ;;
  ;; List communities
  ;;
  (GET "/communities" []
    (let [communities (db/find-communities)]
      (p/render-file "bulletin/views/list_communities.html"
                     {:communities communities})))
  (route/resources "/")
  (route/not-found "Not Found"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn wrap-method-override
  "Ring middleware for method overriding (X-HTTP-Method-Override and _method query parameter)"
  [handler]
  (fn [request]
    (let [method-string (or (get-in request [:headers "x-http-method-override"])
                            (get-in request [:query-params "_method"]))]
      (handler (if method-string
                 (assoc request :request-method (keyword
                                                 (str/lower-case method-string)))
                 request)))))

;; TODO: Write a regexp that catches all assets in one go
(defn ignore-assets [middleware]
  (fn [handler]
    (fn [request]
      (if (re-find #"\.js$|\.css$|\.map$|\.woff$" (:uri request))
        (handler request)
        ((middleware handler) request)))))

(defn wrap-echo [handler]
  (fn [request]
    (println "\n=========================\nRequest:")
    (pprint request)
    (println "flash: " (:flash request))
    (let [response (handler request)]
      (println "\nResponse:" (dissoc response :body))
      response)))

(def app
  ;; TODO: Only use dev middleware in dev mode, not in production
  (-> app-routes ;(routes home-routes app-routes)
      ((ignore-assets wrap-current-user))
      ((ignore-assets wrap-current-community))
      (wrap-method-override)
      ((ignore-assets wrap-echo))
      (handler/site {:session {:cookie-name "bulletin-session"
                               :cookie-attrs {:domain
                                              ;; FIXME: Dumb way to extract foo.com
                                              ;; from foo.com:3000
                                              (let [cookie-domain (-> config/app-domain
                                                                      (str/split #":")
                                                                      first)]
                                                (str "." cookie-domain))}
                               :store (cookie-store
                                       ;; 16-byte secret
                                       {:key "abcdefghijklmnop"})}})
      (wrap-base-url)
      (wrap-reload)
      (prone/wrap-exceptions)))

(defn -main [& args]
  (run-server #'app {:port config/port})
  (println "Bulletin started on" config/port))
