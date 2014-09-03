(defproject bulletin "0.1.0-SNAPSHOT"
  :description "a bolt-on community system"
  :url "http://github.com/danneu/bulletin"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [compojure "1.1.6"]
                 [hiccup "1.0.5"]
                 [selmer "0.6.9"]
                 [org.clojure/java.jdbc "0.3.5"]
                 [postgresql/postgresql "8.4-702.jdbc4"]
                 [prone "0.4.0"]
                 [environ "1.0.0"]
                 [ring-server "0.3.1"]]
  :plugins [[lein-ring "0.8.10"]]
  :ring {:handler bulletin.handler/app
         :init bulletin.handler/init
         :destroy bulletin.handler/destroy}
  ;; :aot :all
  :profiles
  {:production
   {:ring
    {:open-browser? false, :stacktraces? false, :auto-reload? false}}
   :dev
   {:dependencies [[ring-mock "0.1.5"] [ring/ring-devel "1.2.1"]]}})
