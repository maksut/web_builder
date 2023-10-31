(ns app
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [reitit.core :as reitit]
   [reitit.ring.malli]
   [hiccup2.core :refer [html, raw]]
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

(defn html-response [status & content]
  {:headers {"Content-Type" "text/html"}
   :status status
   :body
   (str (html {:mode :html}
              (when (= :html (first content)) ;; if this is full page
                (raw "<!DOCTYPE html>\n"))    ;; then add the doctype
              content))})

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

(defn htmx [request & content]
  (html-response
   200
   (if (is-hx-request request)
     content
     (full-page content))))

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

(defn unzip [builder-dir filename]
  (cond
    (string/ends-with? filename ".zip") (shell/sh "unzip" filename :dir builder-dir)
    (or (string/ends-with? filename ".tgz")
        (string/ends-with? filename ".tar.gz")) (shell/sh "tar" "zxvf" filename :dir builder-dir)
    (string/ends-with? filename ".gz") (shell/sh "gzip -d" filename :dir builder-dir)))

(defn make-command [src-dir]
  (mapcat
   #(string/split % #"\s+")
   ["podman run --rm " ; remove container after exit
    (str "-v " src-dir ":/game/src") ; volume mapping
    "-w /game/src raylib:web make PLATFORM=PLATFORM_WEB"])) ; make in working directory

; (make-command (.toPath (io/file "/home/maksut/oss/raylib-game-template/src")))

(defn make [^File src-dir]
  (let [src-dir (.getAbsoluteFile src-dir)
        command (vec (make-command src-dir))
        command (conj command :dir (str src-dir))
        {:keys [exit out err]} (apply shell/sh command)]
    (with-open [writer (io/writer (io/file src-dir "make_output.txt"))]
      (.write writer (str "EXIT: " exit "\n"))
      (.write writer (str "OUT: " out "\n"))
      (.write writer (str "ERR: " err "\n")))))

; (let [src (find-src-dir (io/file "/home/maksut/oss/raylib-game-template/src"))]
;   (make src))

(defn relative-path [^File relative-to ^File file]
  (.relativize (.toPath relative-to) (.toPath file)))

(defn encode-path [^Path path]
  (let [segments (iterator-seq (.iterator path))
        encoded (map codec/url-encode segments)]
    (string/join "/" encoded)))

(defn find-src-dir [^File dir]
  (let [src? (fn [^File f] (and (.isDirectory f) (= "src" (.getName f))))
        depth (fn [^File f] (-> f .toPath .getNameCount))
        all-src-dirs (filter src? (file-seq dir))]
    (when (seq all-src-dirs)
      (apply min-key depth all-src-dirs))))

(defn upload-file [builder-dir {:keys [^File tempfile filename]}]
  (io/copy tempfile (io/file builder-dir filename))
  (unzip builder-dir filename)
  (when-let [src-dir (find-src-dir builder-dir)]
    (make src-dir)))

; (let [src (find-src-dir (io/file "/home/maksut/projects/web_build/datadir/ytyg0t_XTr2IYKf2QCy5_g"))]
;   (make src))

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

(defn list-files [builder-dir]
  (->> builder-dir
       file-seq
       (filter (fn [^File f] (.isFile f))) ; skipping folders, keeping only files
       (map (partial relative-path builder-dir))))

(defn builder-get [request]
  (let [builder-id (-> request :path-params :builder-id)
        builder-dir (get-builder-dir builder-id)
        src-dir (find-src-dir builder-dir)
        src-dir (and src-dir (relative-path builder-dir src-dir))
        upload-path (get-path request ::builder-upload-post {:builder-id builder-id})]
    (htmx
     request
     [:form {:id "upload-form" :enctype "multipart/form-data" :action upload-path :method "post"}
      [:div
       [:label {:for "file"} "Upload game source code"]
       [:input {:type "file" :name "file" :multiple true}]
       [:button {:type "submit"} "Upload"]
       [:progress {:id "upload-progress" :value "0" :max "100"}]]
      (when src-dir [:p (str "\"src\" dir: " (str src-dir))])]
     [:script
      (raw "htmx.on('#upload-form', 'htmx:xhr:progress', function(evt) { htmx.find('#upload-progress').setAttribute('value', evt.detail.loaded/evt.detail.total * 100); });")]
     [:ul
      (map (fn [rel-path]
             ;; manually crafting the URL here because reitit insists on encodeing file fragment
             [:li [:a {:href (str "/b/" builder-id "/f/" (encode-path rel-path))} (str rel-path)]])
           (list-files builder-dir))])))

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
