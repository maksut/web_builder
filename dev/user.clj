(ns user
  (:require
   [server]
   [integrant.core :as ig]))

(defonce server (atom nil))

(defn restart!
  ([] (swap! server restart!))
  ([server]
   (when server
     (ig/halt! server))
   (println "loading namespaces:" (ig/load-namespaces server/system-config))
   (ig/init server/system-config)))

;; will be called with a shortcut key
#_:clj-kondo/ignore
(defn user []
  (restart!)
  nil)
