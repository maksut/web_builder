(ns app
  (:require
   [reitit.core :as reitit]
   [reitit.ring.malli]
   [hiccup2.core :refer [html, raw]]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [ring.util.codec :as codec]
   [ring.util.io]
   [ring.util.response :as response])
  (:import
   [java.io File]
   [java.nio.file Path]))

(set! *warn-on-reflection* true)

(def datadir (io/file "datadir"))

(defn throw-response! [response]
  (throw (ex-info (str "HTTP " (:status response))
                  {:type :reitit.ring/response
                   :response response})))

(defn throw-bad-request! [body]
  (throw-response! {:status 400 :body body}))

(defn throw-not-found! [body]
  (throw-response! {:status 404 :body body}))

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
  (not= "false" (get headers "hx-request")))

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

(string/ends-with? "testing.zip" ".zip")

(defn unzip [builder-dir filename]
  (cond
    (string/ends-with? filename ".zip") (shell/sh "unzip" filename :dir builder-dir)
    (or (string/ends-with? filename ".tgz")
        (string/ends-with? filename ".tar.gz")) (shell/sh "tar" "zxvf" filename :dir builder-dir)
    (string/ends-with? filename ".gz") (shell/sh "gzip -d" filename :dir builder-dir)))

(defn upload-file [builder-dir {:keys [^File tempfile filename]}]
  (io/copy tempfile (io/file builder-dir filename))
  (unzip builder-dir filename))

(defn make-command [builder-dir]
  (mapcat
   #(string/split % #"\s+")
   ["podman run --rm " ; remove container after exit
    (str "-v " builder-dir "/src:/game/src") ; volume mapping
    "-w /game/src raylib:web make PLATFORM=PLATFORM_WEB"])) ; make in working directory

; (make-command (io/file "/home/maksut/oss/raylib-game-template"))

(defn make [builder-dir]
  (apply shell/sh (make-command builder-dir)))

(defn assert-file-in-parent! [^File file ^File parent]
  (let [parent-canon (.getCanonicalPath parent)
        file-canon (.getCanonicalPath file)]
    (when-not (string/starts-with? file-canon parent-canon)
      (throw-bad-request! {})) ; if outside the datadir then 403
    (when-not (.exists file)
      (throw-not-found! {})))) ; if not exists then 404

(defn get-builder-dir [builder-id]
  (let [builder-dir (io/file datadir builder-id)]
    (assert-file-in-parent! builder-dir datadir)
    builder-dir))

(defn builder-upload-post [request]
  (let [file-param (-> request :parameters :multipart :file) ; can be a single for or multiple files
        files (if (vector? file-param) file-param [file-param]) ; convert that into a file seq
        files (filter #(-> % :filename seq) files) ; filter out ones with empty names
        builder-id (-> request :path-params :builder-id)
        builder-dir (get-builder-dir builder-id)
        builder-path (get-path request ::builder-get {:builder-id builder-id})]
    (dorun (map (partial upload-file builder-dir) files)) ; copy all non-empty files under the builder dir
    (response/redirect builder-path :see-other)))

(defn builder-post [request]
  (let [id (new-builder-id!)
        builder-dir (io/file datadir id)
        builder-path (get-path request ::builder-get {:builder-id id})]
    (.mkdirs builder-dir) ; create the builder dir if not exists
    (response/redirect builder-path :see-other)))

(defn relative-path [^File relative-to ^File file]
  (.relativize (.toPath relative-to) (.toPath file)))

(defn encode-path [^Path path]
  (let [segments (iterator-seq (.iterator path))
        encoded (map codec/url-encode segments)]
    (string/join "/" encoded)))

(defn list-files [builder-dir]
  (->> builder-dir
       file-seq
       (filter (fn [^File f] (.isFile f))) ; skipping folders, keeping only files
       (map (partial relative-path builder-dir))))

(defn find-src-dir [^File dir]
  (let [src? (fn [^File f] (and (.isDirectory f) (= "src" (.getName f))))
        depth (fn [^File f] (-> f .toPath .getNameCount))
        all-src-dirs (filter src? (file-seq dir))
        first-src (apply max-key depth all-src-dirs)]
    (when first-src (relative-path dir first-src))))

(defn builder-get [request]
  (let [builder-id (-> request :path-params :builder-id)
        builder-dir (get-builder-dir builder-id)
        src-dir (find-src-dir builder-dir)
        upload-path (get-path request ::builder-upload-post {:builder-id builder-id})]
    {:body (html5
            (full-page
             [:form {:enctype "multipart/form-data" :action upload-path :method "post"}
              [:div
               [:label {:for "file"} "Upload game source code"]
               [:input {:type "file" :name "file" :multiple true}]
               [:button {:type "submit"} "Upload"]]
              (when src-dir [:p (str "\"src\" dir: " (str src-dir))])]
             [:ul
              (map (fn [rel-path]
                     ;; manually crafting the URL here because reitit insists on encodeing file fragment
                     [:li [:a {:href (str "/b/" builder-id "/f/" (encode-path rel-path))} (str rel-path)]])
                   (list-files builder-dir))]))}))

(defn builder-file-get [{{:keys [builder-id file]} :path-params}]
  (let [builder-dir (get-builder-dir builder-id)
        file (io/file builder-dir file)]
    (assert-file-in-parent! file builder-dir)
    {:body (ring.util.io/piped-input-stream
            (fn [ostream] (io/copy file ostream)))}))

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
            :handler builder-upload-post}}]
   ["/b/:builder-id/f/*file"
    {:name ::builder-file-get
     :get {:parameters {:path [:map [:builder-id builder-id-spec]]}
           :handler builder-file-get}}]])

(comment
  (let [router (reitit/router routes)]
    (->
     (reitit/match-by-name router ::builder-upload-post {:builder-id (new-builder-id!)})
     (reitit/match->path)))

  (let [router (reitit/router routes)]
    (->
     (reitit/match-by-name router ::builder-file-get {:builder-id (new-builder-id!) :file "testing/game.html"})
     #_(reitit/match->path)))

  (let [router (reitit/router routes)]
    (reitit/match-by-path router "/b/uGtxV1WHTeenblTRFsY8Ng/f/keyboard.pdf")))
