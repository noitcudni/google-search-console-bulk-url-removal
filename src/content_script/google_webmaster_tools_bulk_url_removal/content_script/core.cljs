(ns google-webmaster-tools-bulk-url-removal.content-script.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan] :as async]
            [hipo.core :as hipo]
            [dommy.core :refer-macros [sel sel1] :as dommy]
            [testdouble.cljs.csv :as csv]
            ;; [cognitect.transit :as t]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
            [google-webmaster-tools-bulk-url-removal.background.storage :refer [clear-victims! print-victims update-storage
                                                                                current-removal-attempt]]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            ))

;; TODO: add a pause button in the popup
;; TODO: store the to-be-removed urls into local-storage
;; TODO: need a way to clear all history
;; TODO: show the ones that are skipped due to history
;; TODO: provide a way to search and toggle history
;; TODO: if reaches quota limit, pause automatically

; -- a message loop ---------------------------------------------------------------------------------------------------------

(defn process-message! [chan message]
  (let [{:keys [type] :as whole-msg} (common/unmarshall message)]
    (prn "CONTENT SCRIPT: process-message!: " whole-msg)
    (cond (= type :done-init-victims) (post-message! chan (common/marshall {:type :next-victim}))
          (= type :remove-url) (do (prn "handling :remove-url")
                                   (dommy/set-value! (sel1 "input[name=\"urlt\"]") (:victim whole-msg))
                                   (.click (sel1 "input[name=\"urlt.submitButton\"]"))
                                   )
          )
    ))


; -- custom ui components  ------------------------------------------------------------------------------------------------
(def upload-chan (chan 1 (map (fn [e]
                                (let [target (.-currentTarget e)
                                      file (-> target .-files (aget 0))]
                                  (set! (.-value target) "")
                                  file
                                  )))))

(def read-chan (chan 1 (map #(-> % .-target .-result js->clj))))


(defn setup-ui [background-port]
  (let [file-input-el (hipo/create [:div
                                    [:input {:id "fileInput" :type "file"
                                             :on-change (fn [e] (put! upload-chan e))
                                             }]])
        drop-down-el (hipo/create [:div
                                   [:select {:id "global-removal-method"}
                                    [:option {:value "PAGE"}
                                     "Remove page from search results and cache"]
                                    [:option {:value "PAGE_CACHE"}
                                     "Remove page from cache only"]
                                    [:option {:value "DIRECTORY"}
                                     "Remove Directory"]]])

        clear-db-btn-el (hipo/create [:div
                                      [:button {:type "button"
                                                :on-click (fn [_]
                                                            (log "clear victims from local storage.") ;;xxx
                                                            (clear-victims!)
                                                            )}
                                       "Clear local storage"]])
        print-db-btn-el (hipo/create [:div
                                      [:button {:type "button"
                                                :on-click (fn [_]
                                                            (log "print db btn clicked!")
                                                            (print-victims))
                                                }
                                       "View local storage"
                                       ]])
        br-el (hipo/create [:br])
        ]
    (dommy/append! (sel1 :#create-removal_button) (hipo/create [:br]))
    (dommy/append! (sel1 :#create-removal_button) file-input-el)
    (dommy/append! (sel1 :#create-removal_button) (hipo/create [:br]))
    (dommy/append! (sel1 :#create-removal_button) drop-down-el)
    (dommy/append! (sel1 :#create-removal_button) (hipo/create [:br]))
    (dommy/append! (sel1 :#create-removal_button) (hipo/create [:br]))
    (dommy/append! (sel1 :#create-removal_button) clear-db-btn-el)
    (dommy/append! (sel1 :#create-removal_button) (hipo/create [:br]))
    (dommy/append! (sel1 :#create-removal_button) print-db-btn-el)
    ))

(defn setup-continue-ui [background-port]
  (let [continue-button-el (hipo/create [:div [:button {:type "button"
                                                        :on-click (fn []
                                                                    (post-message! background-port (common/marshall {:type :next-victim}))
                                                                    )}
                                               "Continue"
                                               ]])]
    (dommy/append! (sel1 :#create-removal_button) continue-button-el)
    ))


(defn update-removal-status []
  ;; the status message should read "https://polymorphiclabs.io/tags-output/mobile%20app/ has been added for removal."
  (go
    (prn "update-removal-status: " (sel1 ".status-message-text"))
    (when-let [el (sel1 ".status-message-text")]
      (let [txt (dommy/text el)
            _ (prn "update-removal-status: txt: " txt) ; xxx
            [url & more] (clojure.string/split txt #" ")]

        (when (re-find #"http" url) ;; make sure that it starts with http
          (<! (update-storage url
                              "status" "removed"
                              "remove-ts" (tc/to-long (t/now))
                              )))
        ))))

; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (let [_ (log "CONTENT SCRIPT: init")
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
            _ (prn (clojure.string/trim file-content))
            csv-data (->> (csv/read-csv (clojure.string/trim file-content))
                          ;; trim off random whitespaces
                          (map (fn [[url method]]
                                 (->> [(clojure.string/trim url)
                                       (when method (clojure.string/trim method))]
                                      (filter (complement nil?))
                                      ))))]
        (post-message! background-port (common/marshall {:type :init-victims
                                                         :global-removal-method (dommy/value (sel1 :#global-removal-method))
                                                         :data csv-data
                                                         }))
        (recur)))

    ;; Ask for the next victim if there's no failure.
    ;; If current-removal-attempt returns nil, that means
    ;; that there's no outstanding failure.
    (go
      (<! (async/timeout 1500)) ;; wait a bit for the ui to update
      (<! (update-removal-status))
      (setup-ui background-port)
      (common/connect-to-background-page! background-port process-message!)

      (let [_ (prn "Inside go block.") ;;xxx
            curr-removal (<! (current-removal-attempt))
            _ (prn "curr-removal: " curr-removal) ;;xxx
            outstanding-failed-attempt? (->> curr-removal empty? not)]
        (if outstanding-failed-attempt?
          (setup-continue-ui background-port) ;; pause since we have an outstanding failure.
          (post-message! background-port (common/marshall {:type :next-victim}))
          )))
    ))
