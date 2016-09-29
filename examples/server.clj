(ns examples.server
  (:require [ring.adapter.jetty :as jetty]
            [compojure.route :as route]
            [clj-zipkin.middleware :as m]
            [clj-zipkin.tracer :as t])
  (:use [compojure.core :only (GET PUT POST ANY defroutes context)]))

(defonce server nil)

(def config {:scribe {:host "localhost" :port 9410}
             :service "TestService"})

(defn normal-fun
  []
  (t/trace {:span "GET"
            :host {:service "RendererService"}
            :scribe (:scribe config)}
           (do
               (Thread/sleep 100)
               "<h1>Hello World</h1>")))

(defn main-renderer
  [request]
  ;;functions traced during request processing
  ;;can take zipking parameters from request                                      
  (Thread/sleep 500)
  (t/trace {:span "Renderer" 
            ;;this values should be propagated over the network in
            ;;case execution continues somewhere else
            :trace-id (get-in request [:zipkin :trace-id])
            :parent-span-id (get-in request [:zipkin :span-id])
            ;specify only service name, default other parameters
            :host {:service "RendererService"}
            :scribe (:scribe config)}
           (do
             (Thread/sleep 100)
             "<h1>Hello World</h1>")))

(defroutes routes
  (GET "/" request (main-renderer request))
  (route/not-found "<h1>Page not found</h1>"))

(def app (-> routes 
             (m/request-tracer config)))

(defn start-server [port]
  ;; FIXME: Make this more robust by using `start-server-or-die`
  (let [server (jetty/run-jetty
                app
                {:port port
                 :join? false})]
    (alter-var-root #'server (constantly server))))


(defn stop-server [server]
  (.stop ^org.eclipse.jetty.server.Server server))
