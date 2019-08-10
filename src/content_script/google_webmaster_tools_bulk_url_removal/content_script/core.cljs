(ns google-webmaster-tools-bulk-url-removal.content-script.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan]]
            [hipo.core :as hipo]
            [dommy.core :refer-macros [sel sel1] :as dommy]
            [testdouble.cljs.csv :as csv]
            [cognitect.transit :as t]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [google-webmaster-tools-bulk-url-removal.background.storage :refer [clear-victims! print-victims]]
            ))

;; TODO: add a pause button in the popup
;; TODO: store the to-be-removed urls into local-storage
;; TODO: need a way to clear all history
;; TODO: show the ones that are skipped due to history
;; TODO: provide a way to search and toggle history
;; TODO: if reaches quota limit, pause automatically

; -- a message loop ---------------------------------------------------------------------------------------------------------

(defn process-message! [message]
  (log "CONTENT SCRIPT: got message:" message))

(defn run-message-loop! [message-channel]
  (log "CONTENT SCRIPT: starting message loop...")
  (go-loop []
    (when-some [message (<! message-channel)]
      (process-message! message)
      (recur))
    (log "CONTENT SCRIPT: leaving message loop")))

; -- custom ui components  ------------------------------------------------------------------------------------------------
(def upload-chan (chan 1 (map (fn [e]
                                (let [target (.-currentTarget e)
                                      file (-> target .-files (aget 0))]
                                  (set! (.-value target) "")
                                  file
                                  )))))

(def read-chan (chan 1 (map #(-> % .-target .-result js->clj))))


(defn setup-ui [background-port]
  (let [file-input-el (hipo/create [:div [:input {:id "fileInput" :type "file"
                                                  :on-change (fn [e] (put! upload-chan e))
                                                  }]])
        drop-down-el (hipo/create [:div [:select {:id "global-removal-method"}
                                         [:option {:value "PAGE"}
                                          "Remove page from search results and cache"]
                                         [:option {:value "PAGE_CACHE"}
                                          "Remove page from cache only"]
                                         [:option {:value "DIRECTORY"}
                                          "Remove Directory"]]])

        clear-db-btn-el (hipo/create [:div [:button {:type "button"
                                                     :on-click (fn [_]
                                                                 (log "clear db btn clicked!")
                                                                 ;; (clear-victims!)
                                                                 )}
                                            "Clear local storage"]
                                      ])
        print-db-btn-el (hipo/create [:div [:button {:type "button"
                                                     :on-click (fn [_]
                                                                 (log "print db btn clicked!")
                                                                 (print-victims))
                                                     }
                                            "View local storage"
                                            ]])]
    (dommy/append! (sel1 :#create-removal_button) file-input-el)
    (dommy/append! (sel1 :#create-removal_button) drop-down-el)
    (dommy/append! (sel1 :#create-removal_button) clear-db-btn-el)
    (dommy/append! (sel1 :#create-removal_button) print-db-btn-el)
    ))

(defn connect-to-background-page! [background-port]
  ;; (post-message! background-port "hello from CONTENT SCRIPT!")
  (run-message-loop! background-port)
  (setup-ui background-port))

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
            w (t/writer :json)
            _ (prn (clojure.string/trim file-content))
            csv-data (->> (csv/read-csv (clojure.string/trim file-content))
                          ;; trim off random whitespaces
                          (map (fn [[url method]]
                                 (->> [(clojure.string/trim url)
                                       (when method (clojure.string/trim method))]
                                      (filter (complement nil?))
                                      ))))
            ]
        (post-message! background-port (t/write w {:type :init-victims
                                                   :global-removal-method (dommy/value (sel1 :#global-removal-method))
                                                   :data csv-data
                                                   }))
        (recur)))

    (connect-to-background-page! background-port)))
