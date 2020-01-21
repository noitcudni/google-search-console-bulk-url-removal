(ns google-webmaster-tools-bulk-url-removal.popup.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [<!]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [chromex.ext.downloads :refer-macros [download]]
            [re-com.core :as recom]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
            [reagent.core :as reagent :refer [atom]]
            ))

; -- a message loop ---------------------------------------------------------------------------------------------------------

(defn process-message! [message]
  (let [{:keys [type] :as whole-edn} (common/unmarshall message)
        _ (prn "message: " (common/unmarshall message))
        ]
    ;; TODO display errors in popup
    (cond (= type :init-errors) (do (prn "cond: " whole-edn))
          (= type :new-error) (do "cond: " (prn whole-edn))
          )
    ))

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

(defn current-page []
  (let [download-fn (fn []
                      (let [content "csv content goes here"
                            data-blob (js/Blob. (clj->js [content])
                                                (clj->js {:type "text/csv"}))
                            url (js/URL.createObjectURL data-blob)
                            ]
                        (download (clj->js {:url url
                                            :filename "error.csv"
                                            :saveAs true
                                            :conflictAction "overwrite"
                                            }))

                        ))]
    (fn []
      [recom/v-box
       :width "360px"
       :align :center
       :children [
                  [recom/h-box
                   :children [[recom/title :label "Error Count:" :level :level1]
                              [recom/title :label "4" :level :level1]]]
                  ;; [recom/h-box
                  ;;  :children [[:h1 "Removed Count: "] [:h1 "5"]]]
                  [recom/button
                   :label "Download CSV"
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
