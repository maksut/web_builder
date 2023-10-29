(ns app
  (:require
   [reitit.core :as reitit]
   [reitit.ring.malli]
   [hiccup2.core :refer [html, raw]]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [ring.util.response :as response]
   [ring.util.codec :as codec])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(def datadir (.getCanonicalPath (io/file "datadir")))

(defn get-path [{::reitit/keys [router]} path-name path-params]
  (-> router
      (reitit/match-by-name path-name path-params)
      (reitit/match->path)))

(defn html5 [content]
  (str (html {:mode :html}
             (when (= :html (first content)) ;; if this is full page
               (raw "<!DOCTYPE html>\n"))    ;; then add the doctype
             content)))

(defn full-page [& content]
  [:html
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/water.css@2/out/water.css"}]
    [:script {:src "https://unpkg.com/htmx.org@1.9.5" :integrity "sha384-xcuj3WpfgjlKF+FXhSQFQ0ZNr39ln+hwjN3npfM9VBnUskLolQAcN80McRIVOPuO" :crossorigin= "anonymous"}]]
   [:body content]])

(defn is-hx-request [{:keys [headers]}]
  (= "true" (get headers "hx-request")))

(defn htmx-view [request response-body]
  {:body (if (is-hx-request request)
           response-body
           (full-page response-body))})

(defn uuid->base64 [^java.util.UUID uuid]
  (let [base64 (.withoutPadding (java.util.Base64/getUrlEncoder))
        encode (fn [bytes] (.encodeToString base64 bytes))]
    (-> (java.nio.ByteBuffer/allocate 16)
        (.putLong (.getMostSignificantBits uuid))
        (.putLong (.getLeastSignificantBits uuid))
        (.array)
        (encode))))

(defn new-builder-id! []
  (uuid->base64 (java.util.UUID/randomUUID)))

(defn upload-file [builder-dir {:keys [^File tempfile filename]}]
  (io/copy tempfile (io/file builder-dir filename)))

(defn make-command [builder-dir]
  (mapcat
   #(string/split % #"\s+")
   ["podman run --rm " ; remove container after exit
    (str "-v " builder-dir "/src:/game/src") ; volume mapping
    "-w /game/src raylib:web make PLATFORM=PLATFORM_WEB"])) ; make in working directory

(make-command (io/file "/home/maksut/oss/raylib-game-template"))

(defn make [builder-dir]
  (apply shell/sh (make-command builder-dir)))

(defn get-builder-dir [builder-id]
  (let [builder-dir (io/file datadir builder-id)
        builder-dir-canon (.getCanonicalPath builder-dir)]
    (when-not (string/starts-with? builder-dir-canon datadir)
      (throw (ex-info "Not found" {:status 404})))
    builder-dir))

(defn buldier-upload-post [request]
  (let [file-param (-> request :parameters :multipart :file)
        files (if (vector? file-param) file-param [file-param])
        files (filter #(-> % :filename empty? not) files) ; filter out records with empty names
        builder-id (-> request :path-params :builder-id)
        builder-dir (get-builder-dir builder-id)
        builder-path (get-path request ::builder-get {:builder-id builder-id})]
    (dorun (map (partial upload-file builder-dir) files))
    (response/redirect builder-path :see-other)))

(defn builder-post [request]
  (let [id (new-builder-id!)
        builder-dir (io/file datadir id)
        builder-path (get-path request ::builder-get {:builder-id id})]
    (.mkdirs builder-dir)
    (response/redirect builder-path :see-other)))

(defn relative-path [^File relative-to ^File file]
  (.toString (.relativize (.toPath relative-to) (.toPath file))))

(defn list-files [builder-id]
  (let [builder-dir (get-builder-dir builder-id)]
    (->> builder-dir
         file-seq
         rest ; skip the builder dir itself
         (map (partial relative-path builder-dir)))))

(defn builder-get [request]
  (let [builder-id (-> request :path-params :builder-id)
        upload-path (get-path request ::builder-upload-post {:builder-id builder-id})]
    {:status 200
     :body (html5
            (full-page
             [:form {:enctype "multipart/form-data" :action upload-path :method "post"}
              [:div
               [:label {:for "file"} "Upload game source code"]
               [:input {:type "file" :name "file" :multiple true}]
               [:button {:type "submit"} "Upload"]]]
             [:ul
              (map (fn [file] [:li (codec/url-encode file)]) (list-files builder-id))]))}))

(def builder-id-spec
  [:re #"^[a-zA-Z0-9_\-]{22}$"])

(def file-spec
  [:multi {:dispatch map?}
   [true reitit.ring.malli/temp-file-part]
   [false [:vector reitit.ring.malli/temp-file-part]]])

(def routes
  [["/ping" {:get {:handler (fn [_] {:status 200 :body "pong!"})}}]
   ["/fail" {:get {:handler (fn [_] (throw (ex-info "ha! exception" {:because "reasons"})))}}]
   ["/b" {:name ::builder-post
          :post {:handler builder-post}}]
   ["/b/:builder-id"
    {:name ::builder-get
     :get {:parameters {:path [:map [:builder-id builder-id-spec]]}
           :handler builder-get}}]
   ["/b/:builder-id/upload"
    {:name ::builder-upload-post
     :post {:parameters {:path [:map [:builder-id builder-id-spec]]
                         :multipart [:map [:file file-spec]]}
            :handler buldier-upload-post}}]])

(comment
  (let [router (reitit/router routes)]
    (->
     (reitit/match-by-name router ::builder-upload-post {:builder-id (new-builder-id!)})
     (reitit/match->path))))
