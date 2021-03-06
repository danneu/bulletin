(defproject bulletin "0.1.0-SNAPSHOT"
  :description "a bolt-on community system"
  :url "http://github.com/danneu/bulletin"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [compojure "1.1.6"]
                 [hiccup "1.0.5"]
                 [selmer "0.6.9"]
                 [org.clojure/java.jdbc "0.3.5"]
                 [cheshire "5.3.1"]
                 [postgresql/postgresql "8.4-702.jdbc4"]
                 [prone "0.4.0"]
                 [http-kit "2.1.16"]
                 [environ "1.0.0"]
                 [lib-noir "0.8.6"]
                 ;; Hash library I use for generating avatar hex colors from unames
                 [pandect "0.3.4"]
                 [ring-server "0.3.1"]]
  :plugins [[lein-ring "0.8.10"]]
  :ring {:handler bulletin.handler/app
         :init bulletin.handler/init
         :destroy bulletin.handler/destroy}
  ;; :aot :all
  :main ^:skip-aot bulletin.handler
  :uberjar-name "bulletin-standalone.jar"
  :profiles
  {:uberjar {:aot :all}
   :production
   {:ring
    {:open-browser? false, :stacktraces? false, :auto-reload? false}}
   :dev
   {:dependencies [[ring-mock "0.1.5"] [ring/ring-devel "1.2.1"]]}})
