(defproject org.clojars.aliceliang/clj-zipkin "0.1.4"
  :description "Zipkin tracing instrumentation for Clojure applications."
  :url "https://github.com/guilespi/clj-zipkin"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.zipkin.java/zipkin "1.12.1"]
                 [ch.qos.logback/logback-classic "1.1.7"]                 
                 [org.clojure/data.codec "0.1.0"]
                 [clj-scribe "0.3.1"]
                 [clj-time "0.12.0"]]
  :test-paths ["test"]
  :profiles {:dev {:dependencies [[ring/ring-mock "0.3.0"]
                                  [compojure "1.5.1"]
                                  [ring "1.5.0"]]}}
  :plugins [[lein-ring "0.9.7"]]
  :javac-options ["-Xlint:unchecked"])
