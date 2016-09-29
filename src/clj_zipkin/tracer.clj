(ns clj-zipkin.tracer
  (:require [clojure.data.codec.base64 :as b64]
            [clj-scribe :as scribe]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce])
  (:import (zipkin Span Annotation Endpoint Codec BinaryAnnotation)
           (java.nio ByteBuffer)))

;(def bann (BinaryAnnotation. "name" (byte-streams/convert "abcd" java.nio.ByteBuffer) AnnotationType/STRING nil))

(def ^:dynamic *default-service-name* "unknown")
(def ^:dynamic *default-component-name* "")

(defn ip-str-to-int
  [str-ip]
  (let [nums (vec (map read-string (clojure.string/split str-ip #"\.")))]
    (+
     (* (nums 0) 16777216)
     (* (nums 1) 65536)
     (* (nums 2) 256)
     (nums 3))))

(defn str->bytes
  [str]
  (bytes (byte-array (map (comp byte int) str))))

(defn thrift->base64
  "Serializes a thrift span object to be sent through the wires"
  [span]
  (let [bytes (.writeSpan Codec/THRIFT span)]
    (String. (b64/encode bytes) "UTF-8")))

(defn host->endpoint
  "Given a host in string or map format creates a thrift zipkin endpoint object"
  [host]
  (let [array-map? (= (class host) clojure.lang.PersistentArrayMap)
        service-name (if array-map? (:service host) *default-service-name*)
        port (if array-map? (:port host) 0)
        ipv4 (if array-map?
               (ip-str-to-int (or (:ipv4 host) (.getHostAddress (java.net.InetAddress/getLocalHost)))))]
    (.. (Endpoint/builder)
        (serviceName service-name)
        (port port)
        (ipv4 ipv4)
        (build))))

;;According to tryfer sources, zipkin has trouble recording traces with ids
;;larger than (2 ** 56) - 1
(def rand-max (dec (Math/pow 2 24)))

(defn create-id
  "Creates a new id to be used as trace or span id"
  []
  (rand-int rand-max))

(defn create-timestamp-span
  "Creates a new span with start/finish annotations"
  ([span host trace-id span-id parent-id start finish]
   (create-timestamp-span span host trace-id span-id parent-id start finish {}))
  ([span host trace-id span-id parent-id start finish & [annotations]]
   (let  [endpoint (host->endpoint host)
          start-timestamp (* 1000 (time-coerce/to-long start))
          end-timestamp (* 1000 (time-coerce/to-long finish))
          binary-annotations (if (empty? annotations)
                               [(BinaryAnnotation/create "lc" *default-component-name* endpoint)]
                               (for [[k v] annotations]
                                 (BinaryAnnotation/create (name k) (name v) endpoint)))]
     (thrift->base64
      (.. (Span/builder)
          (traceId (long trace-id))
          (id span-id)
          (parentId (long (if parent-id parent-id 0)))
          (timestamp (long start-timestamp))
          (duration (long (- end-timestamp start-timestamp)))
          (name span)
          (binaryAnnotations binary-annotations)
          (build))))))

;;tracing macro for nested recording

(defmulti parse-item (fn [form]
                       (cond
                        (seq? form) :seq
                        (vector? form) :vector
                        :else :default)))

;;if found a call to original trace in the ast
;;change for a call to trace* in order to use
;;only one logger at the top
(defmethod parse-item :seq
  [form]
  (if (and (symbol? (first form))
           (= (ns-resolve *ns* (first form))
              (ns-resolve 'clj-zipkin.tracer 'trace)))
    (let [[_ data body] form]
      (list* 'clj-zipkin.tracer/trace*
             data
             (doall (map parse-item body))
             '()))
    (doall (map parse-item form))))

(defmethod parse-item :vector
  [v]
  (vec (doall (map parse-item v))))

(defmethod parse-item :default
  [item]
  item)

(defn make-logger
  "Creates a new scribe connection object, config should be a
   map with scribe endpoint configuration:

   => {:host h :port p}"
  ([config]
     (make-logger config "zipkin"))
  ([config category]
     (scribe/async-logger :host (:host config)
                          :port (:port config)
                          :category category)))

(defn log
  "Forward log to scribe"
  [connection span-list]
  (scribe/log connection span-list))

(defmacro trace*
  "Creates a start/finish timestamp annotations span
   for the code chunk received, defers actual logging to upper trace function."
  [{:keys [span host span-id]} & body]
  (let [body (parse-item body)]
    `(let [parent-id# ~'span-id
           ~'span-id (or ~span-id (create-id))
          ; _# (println "t" ~'trace-id "s" ~'span-id "p" parent-id#)
           start-time# (time/now)
           result# ~@body
           end-time# (time/now)
           span# (create-timestamp-span ~span ~host ~'trace-id
                                        ~'span-id parent-id#
                                        start-time# end-time#)
           ;parent spans added at the end, so cons
           _# (swap! ~'span-list (fn [l#] (cons span# l#)))]
       result#)))

(defmacro trace
  "Timestamp tracing of a code chunk using timestamp annotations.
  => (trace options body)

  options {
  :host => current host, defaults to InetAddress/getLocalHost if unspecifyed
  :span => span name
  :trace-id => optional, new one will be created if unspecifyed
  :scribe => scribe/zipkin endpoint configuration {:host h :port p}
  }

  Macro uses anaphoras for nested tracing so the following variable names are defined:
  * trace-id
  * span-id
  * span-list

  (trace {:host \"10.0.2.1\" :span \"GET\" :scribe {host \"localhost\" :port 9410}}
    (...code to be traced...))

  Trace Id can be specified with the :trace-id option

  (trace {:host \"10.0.2.1\" :trace-id 12345 :span \"GET\" :scribe {host \"localhost\" :port 9410}}
    (...code to be traced...))

  Nested traces are supported and scribe configuration is not needed for inner traces,
  those will be treated as child spans of the immediate parent trace

  (trace {:host \"10.0.2.1\" :span \"GET\" :scribe {host \"localhost\" :port 9410}}
     (...code to be traced...
       (trace {:span \"OTHER\"}
         (..code...))))"
  [& args]
  `(let [logger# ~(if (symbol? (-> args first :scribe))
                    (-> args first :scribe)
                   `(make-logger ~(-> args first :scribe)))
         ~'trace-id (or ~(-> args first :trace-id)
                        (create-id))
         ~'span-list (atom [])
         ~'span-id ~(-> args first :parent-span-id)
         result# (trace* ~(first args) ~@(rest args))
         _# (log logger# (deref ~'span-list))]
     result#))
