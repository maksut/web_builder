(ns server
  (:require
   [app]
   [integrant.core :as ig]
   [muuntaja.core]
   [reitit.core]
   [reitit.coercion.malli]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.dev :as dev]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.multipart :as multipart]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.ring.spec :as spec]
   [ring.adapter.jetty :as jetty])
  (:import [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

(def system-config
  {::jetty   {:port    3000
              :join?   false
              :handler (ig/ref ::handler)}
   ::handler {:routes (ig/ref :app/routes)}
   :app/routes {}})

(defmethod ig/init-key ::jetty [_ {:keys [port join? handler]}]
  (println "starting server in port" port)
  (jetty/run-jetty handler {:port port :join? join?}))

(defmethod ig/halt-key! ::jetty [_ ^Server server]
  (println "stopping the server")
  (.stop server))

(defn exception-middleware [handler ^Throwable e request]
  (let [response (handler e request)
        body (:body response)
        type (:type body)]
    (when-not (or (= type :reitit.coercion/request-coercion)
                  (= type :reitit.coercion/response-coercion))
      (.printStackTrace e))
    ;; dumping the exception response body as a string!
    (assoc response :body (pr-str body))))

(defmethod ig/init-key :app/routes [_ _]
  app/routes)

(defmethod ig/init-key ::handler [_ {:keys [routes]}]
  (ring/ring-handler
   (ring/router
    routes
    {:reitit.middleware/transform dev/print-request-diffs ;; pretty diffs for dev
     :validate spec/validate ;; enable spec validation for route data
     :muuntaja muuntaja.core/instance
     :data {:coercion reitit.coercion.malli/coercion
            :middleware [parameters/parameters-middleware
                         muuntaja/format-middleware
                         (exception/create-exception-middleware
                          (merge
                           exception/default-handlers
                           {::exception/wrap exception-middleware}))
                         coercion/coerce-request-middleware
                         multipart/multipart-middleware]}})
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (ring/create-default-handler))))

(defn -main []
  (ig/init system-config))

(comment
  (def router
    (ring/router ["/public/{*path}"]))

  (reitit.core/match-by-path router "/public/testing/hebele%20/hubele/"))
