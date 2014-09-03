(ns bulletin.config
  (:require [environ.core :refer [env]]))

;; In dev, it should read from .lein-env which contains {:database-url _}
;; In prod, since there is no .lein-env, it will read from env variables
(def database-url
  (env :database-url))

(def port
  (or (env :port) 3000))
