(ns google-webmaster-tools-bulk-url-removal.popup.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [reagent.ratom :refer [reaction]])
  (:require [cljs.core.async :refer [<!]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [chromex.ext.downloads :refer-macros [download]]
            [re-com.core :as recom]
            [testdouble.cljs.csv :as csv]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
            [reagent.core :as reagent :refer [atom]]
            [google-webmaster-tools-bulk-url-removal.background.storage :refer [get-bad-victims]]
            ))

; -- a message loop ---------------------------------------------------------------------------------------------------------
(def cached-bad-victims-atom (atom nil))
(def disable-error-download-ratom? (reagent/atom true))

(defn update-download-btn-ratom []
  (when (> (count @cached-bad-victims-atom) 0)
    (reset! disable-error-download-ratom? false)))

(defn process-message! [message]
  (let [{:keys [type] :as whole-edn} (common/unmarshall message)]
    ;; TODO display errors in popup
    (cond (= type :init-errors) (do (prn "init-errors: " whole-edn)
                                    (reset! cached-bad-victims-atom (-> whole-edn :bad-victims vec))
                                    (update-download-btn-ratom))
          (= type :new-error) (do (prn "new-error: " whole-edn)
                                  (swap! cached-bad-victims-atom conj (:error whole-edn))
                                  (update-download-btn-ratom))
          (= type :done) (do (prn "done: " whole-edn)
                             (update-download-btn-ratom))
          )))

(defn run-message-loop! [message-channel]
  (log "POPUP: starting message loop...")
  (go-loop []
    (when-some [message (<! message-channel)]
      (process-message! message)
      (recur))
    (log "POPUP: leaving message loop")))

(defn connect-to-background-page! []
  (let [background-port (runtime/connect)]
    (post-message! background-port (common/marshall {:type :fetch-initial-errors}))
    (run-message-loop! background-port)))

(defn csv-content [input]
  (->> input
       clojure.walk/keywordize-keys
       (map (fn [[url {:keys [error-reason removal-method] :as v}]]
              [url error-reason removal-method]))))

(defn current-page []
  (let [download-fn (fn []
                      (let [content (-> @cached-bad-victims-atom csv-content csv/write-csv)
                            data-blob (js/Blob. (clj->js [content])
                                                (clj->js {:type "text/csv"}))
                            url (js/URL.createObjectURL data-blob)]
                        (download (clj->js {:url url
                                            :filename "error.csv"
                                            :saveAs true
                                            :conflictAction "overwrite"
                                            }))

                        ))
        cnt-ratom (reaction (count @cached-bad-victims-atom))]
    (fn []
      [recom/v-box
       :width "360px"
       :align :center
       :children [[recom/v-box
                   :align :start
                   :style {:padding "10px"}
                   :children [[recom/title :label "Instructions:" :level :level1]
                              [recom/label :label "Go to your Google Search Account"]
                              [recom/label :label "Click on 'Legacy Tools and Reports > Removals'"]
                              [recom/label :label "Upload your csv file by clicking on the 'Choose file' button"]]]
                  [recom/h-box
                   :children [[recom/title :label "Error Count: " :level :level1]
                              [recom/title :label (str @cnt-ratom) :level :level1]]]
                  [recom/button
                   :label "Download Error CSV"
                   :disabled? @disable-error-download-ratom?
                   :style {:color            "white"
                           :background-color  "#d9534f" #_(if @hover? "#0072bb" "#4d90fe")
                           :font-size        "22px"
                           :font-weight      "300"
                           :border           "none"
                           :border-radius    "0px"
                           :padding          "20px 26px"}
                   :on-click download-fn
                   ]]
       ])))

; -- main entry point -------------------------------------------------------------------------------------------------------
(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (log "POPUP: init")
  (connect-to-background-page!)
  (mount-root))
