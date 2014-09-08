(ns bulletin.config
  (:require [environ.core :as environ]))

;; In dev, it should read from .lein-env which contains {:database-url _}
;; In prod, since there is no .lein-env, it will read from env variables
(def database-url
  (environ/env :database-url))

(def port
  (if (environ/env :port)
    (Integer/parseInt (environ/env :port))
    3000))

(def app-domain
  (or (environ/env :app-domain) "bulletin.dev"))

;; Useful for passing env into templates, particularly to access app-domain
;; TODO: Better solution
(def config
  (merge environ/env
         {:port port
          :app-domain app-domain}))
