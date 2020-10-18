(ns google-webmaster-tools-bulk-url-removal.popup.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [reagent.ratom :refer [reaction]])
  (:require [cljs.core.async :refer [<! >! put! chan] :as async]
            [hipo.core :as hipo]
            [dommy.core :refer-macros [sel sel1] :as dommy]
            [domina :refer [single-node nodes]]
            [domina.xpath :refer [xpath]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [chromex.ext.downloads :refer-macros [download]]
            [chromex.ext.browser-action :refer-macros [set-badge-text]]
            [cemerick.url :refer [url]]
            [re-com.core :as recom]
            [testdouble.cljs.csv :as csv]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
            [reagent.core :as reagent :refer [atom]]
            [google-webmaster-tools-bulk-url-removal.background.storage :refer [clear-victims! print-victims get-bad-victims]]
            ))

; -- setting up channels for csv input  --
(def my-status (atom :done)) ;; :done or :running
(def upload-chan (chan 1 (map (fn [e]
                                (let [target (.-currentTarget e)
                                      file (-> target .-files (aget 0))]
                                  (set! (.-value target) "")
                                  file
                                  )))))

(def read-chan (chan 1 (map #(-> % .-target .-result js->clj))))

; -- a message loop ---------------------------------------------------------------------------------------------------------
(def cached-bad-victims-atom (atom nil))

(defn process-message! [channel message]
  (let [{:keys [type] :as whole-edn} (common/unmarshall message)]
    ;; TODO display errors in popup
    (cond (= type :init-errors) (do (prn "init-errors: " whole-edn)
                                    (reset! cached-bad-victims-atom (-> whole-edn :bad-victims vec)))
          (= type :new-error) (do (prn "new-error: " whole-edn)
                                  (swap! cached-bad-victims-atom conj (->> whole-edn
                                                                           :error
                                                                           (into [])
                                                                           first)))
          (= type :done) (do (prn "done: " whole-edn)
                             (reset! my-status :done))
          ;; (= type :done-init-victims) (post-message! channel (common/marshall {:type :next-victim}))
          )))

(defn run-message-loop! [message-channel]
  (log "POPUP: starting message loop...")
  (go-loop []
    (when-some [message (<! message-channel)]
      (process-message! message-channel message)
      (recur))
    (log "POPUP: leaving message loop")))

(defn connect-to-background-page! [background-port]
  (post-message! background-port (common/marshall {:type :fetch-initial-errors}))
  (run-message-loop! background-port))

(defn csv-content [input]
  (->> input
       clojure.walk/keywordize-keys
       (map (fn [[url {:keys [error-reason removal-method url-type] :as v}]]
              [url error-reason removal-method url-type]))))

(defn current-page []
  (let [disable-error-download-ratom? (reaction (or (not= :done @my-status)
                                                    (zero? (count @cached-bad-victims-atom))))
        download-fn (fn []
                      (let [content (-> @cached-bad-victims-atom csv-content csv/write-csv)
                            data-blob (js/Blob. (clj->js [content])
                                                (clj->js {:type "text/csv"}))
                            url (js/URL.createObjectURL data-blob)]
                        (download (clj->js {:url url
                                            :filename "error.csv"
                                            :saveAs true
                                            :conflictAction "overwrite"
                                            }))))]
    (fn []
      (let [cnt-ratom (reaction (count @cached-bad-victims-atom))]
        [recom/v-box
         :width "380px"
         :align :center
         :children [
                    [:div {:style {:display "none"}}
                     [:input {:id "bulkCsvFileInput" :type "file"
                              :on-change (fn [e]
                                           (reset! my-status :running)
                                           (reset! cached-bad-victims-atom nil)
                                           (put! upload-chan e))
                              }]]
                    [recom/v-box
                     :align :start
                     :style {:padding "10px"}
                     :children [[recom/title :label "Instructions:" :level :level1]
                                [recom/label :label "- Go to Google Search Console."]
                                [recom/label :label "- Select the proper domain from the dropdown on the left."]
                                [recom/label :label "- Select Removals on the left."]
                                [recom/label :label "- Upload your csv file by clicking on the 'Submit CSV File' button. Check out the example csv below."]
                                [recom/hyperlink-href
                                 :label "CSV Format Documentation"
                                 :href "https://github.com/noitcudni/google-search-console-bulk-url-removal/#csv-format"
                                 :target "_blank"]
                                [recom/hyperlink-href
                                 :label "example 1"
                                 :href "examples/most-common.csv"]
                                [recom/hyperlink-href
                                 :label "example 2"
                                 :href "examples/optional-columns.csv"]]
                     ]
                    [recom/v-box
                     :gap "10px"
                     :children [[recom/button
                                 :label "Submit CSV File"
                                 :tooltip [recom/v-box
                                           :children [[recom/label :label "Make sure that you are on "]
                                                      [recom/label :label "Google Search Console's Removals page."]]]
                                 :style {:width "200px"
                                         :background-color "#007bff"
                                         :color "white"}
                                 :on-click (fn [e]
                                             (-> "//input[@id='bulkCsvFileInput']" xpath single-node .click))]
                                [recom/button
                                 :label "Clear cache"
                                 :style {:width "200px"}
                                 :on-click (fn [_]
                                             (clear-victims!)
                                             (set-badge-text #js{"text" ""})
                                             (reset! cached-bad-victims-atom nil))]
                                [recom/button
                                 :label "View cache"
                                 :tooltip [recom/v-box
                                           :children [[recom/label :label "Go to the chrome developer console"]
                                                      [recom/label :label "Press me to see debugging information"]
                                                      ]]
                                 :style {:width "200px"}
                                 :on-click (fn [_]
                                             (print-victims)
                                             (go
                                               (let [bad-victims (<! (get-bad-victims))]
                                                 (prn "bad-victims: "  bad-victims)
                                                 )))]
                                ]]

                    [recom/h-box
                     :children [[recom/title :label "Error Count: " :level :level2]
                                [recom/title :label (str @cnt-ratom) :level :level2]]]
                    [recom/button
                     :label "Download Error CSV"
                     :disabled? @disable-error-download-ratom?
                     :tooltip [recom/v-box
                               :children
                               [[recom/label :label "Click here to download "]
                                [recom/label :label "the list of URLs that errored out."]]]
                     :style {:color            "white"
                             :background-color  "#d9534f"
                             :padding          "10px 16px"}
                     :on-click download-fn]
                    [recom/gap :size "30px"]]
         ]))))

; -- main entry point -------------------------------------------------------------------------------------------------------
(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (let [_ (log "POPUP: init")
        background-port (runtime/connect)]
    ;; handle onload
    (go-loop []
      (let [reader (js/FileReader.)
            file (<! upload-chan)]
        (set! (.-onload reader) #(put! read-chan %))
        (.readAsText reader file)
        (recur)))

    ;; handle read the file
    (go-loop []
      (let [file-content (<! read-chan)
            _ (prn "file-content: " (clojure.string/trim file-content))
            file-content (->> (-> file-content
                                  ;; split by ^M character
                                  (clojure.string/split #"\r"))
                              (map clojure.string/trim)
                              (remove (fn [x]
                                        (zero? (count x))))
                              (clojure.string/join "\n"))
            _ (prn "sanitized file-content: " (clojure.string/trim file-content))
            csv-data (->> (csv/read-csv (clojure.string/trim file-content))
                          ;; trim off random whitespaces
                          (map (fn [[url method url-type]]
                                 (->> [(clojure.string/trim url)
                                       (when method (clojure.string/trim method))
                                       (when url-type (clojure.string/trim url-type))]
                                      (filter (complement nil?))
                                      ))))]
        (log "about to call :init-victims")
        (post-message! background-port (common/marshall {:type :init-victims
                                                         :data csv-data
                                                         }))
        (recur)))

    (connect-to-background-page! background-port)
    (mount-root)))
