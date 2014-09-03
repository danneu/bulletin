(ns bulletin.handler
  (:require [compojure.core :refer [defroutes routes GET POST]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file-info :refer [wrap-file-info]]
            [ring.middleware.reload :refer [wrap-reload]]
            [hiccup.middleware :refer [wrap-base-url]]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [bulletin.routes.home :refer [home-routes]]
            [prone.middleware :as prone]
            [bulletin.config :as config]
            ;; To be extracted
            [selmer.parser :as p]
            [bulletin.db :as db]
            ))
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn init []
  (println "bulletin is starting")
  (println config/database-url)
  ;; Don't cache Selmer templates
  ;; TODO: Only do this in dev
  (p/cache-off!))

(defn destroy []
  (println "bulletin is shutting down"))

(defroutes app-routes
  (GET "/set-cookie" req
    (let [cookies {"foo" {:value "bar"
                          :domain ".bulletin.dev"
                          :http-only true
                          :secure false  ; Don't restrict to https yet
                          :max-age (* 60 10)  ; 10 min (in seconds) til expiration
                         }}]
      {:cookies cookies, :body "Cookie should be set", :status 200}))
  (GET "/reset-db" []
    (db/reset-db!))
  (GET "/" []
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
                         (assoc category :forums (get grouped-forums category-id [])))]
        (p/render-file "bulletin/views/community/homepage.html"
                       {:current-community *current-community*
                        :categories categories}))
      ;; www homepage
      (let [communities (db/find-communities)]
        (p/render-file "bulletin/views/homepage.html"
                       {:communities communities}))))
  (POST "/communities" req
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
                                   :user_id 1})]
      (pr-str community)))
  (GET "/communities" []
    (let [communities (db/find-communities)]
      (p/render-file "bulletin/views/list_communities.html"
                     {:communities communities})))
  (route/resources "/")
  (route/not-found "Not Found"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def app
  (-> app-routes ;(routes home-routes app-routes)
      (handler/site)
      (wrap-base-url)
      (wrap-reload)
      (wrap-current-community)
      (prone/wrap-exceptions)))
