(ns app
  (:require
   [reitit.ring.malli]
   [hiccup2.core :refer [html, raw]]))

(defn html5 [content]
  (str (html {:mode :html}
             (when (= :html (first content)) ;; if this is full page
               (raw "<!DOCTYPE html>\n"))    ;; then add the doctype
             content)))

(defn full-page [content]
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

(defn upload [{{{:keys [file]} :multipart} :parameters}]
  (let [files (if (vector? file) file [file])]
    {:status 200
     :body (html5
            (full-page
             (map (fn [file]
                    [:div [:p "File: " (:filename file)]
                     [:p "Size: " (:size file)]])
                  files)))}))

(def routes
  [["/ping" {:get {:handler (fn [_] {:status 200 :body "pong!"})}}]
   ["/fail" {:get {:handler (fn [_]
                              (throw (ex-info "ha! exception" {:because "reasons"})))}}]
   ["/upload"
    {:post {:parameters {:multipart [:map [:file
                                           [:multi {:dispatch map?}
                                            [true reitit.ring.malli/temp-file-part]
                                            [false [:vector reitit.ring.malli/temp-file-part]]]]]}
            :handler upload}}]])

