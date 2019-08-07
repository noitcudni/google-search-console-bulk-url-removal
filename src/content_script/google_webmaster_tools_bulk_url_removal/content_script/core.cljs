(ns google-webmaster-tools-bulk-url-removal.content-script.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan]]
            [hipo.core :as hipo]
            [dommy.core :refer-macros [sel sel1] :as dommy]
            [testdouble.cljs.csv :as csv]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]))

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

; -- a simple page analysis  ------------------------------------------------------------------------------------------------

(defn do-page-analysis! [background-port]
  (let [script-elements (.getElementsByTagName js/document "script")
        script-count (.-length script-elements)
        title (.-title js/document)
        msg (str "CONTENT SCRIPT: document '" title "' contains " script-count " script tags.")]
    (log msg)
    (post-message! background-port msg)))


(def upload-chan (chan 1 (map (fn [e]
                                (let [target (.-currentTarget e)
                                      file (-> target .-files (aget 0))]
                                  (set! (.-value target) "")
                                  file
                                  )))))

(def read-chan (chan 1 (map #(-> % .-target .-result js->clj))))


(defn setup-ui [background-port]
  (let [el (hipo/create [:input {:id "fileInput" :type "file"
                                 :on-change (fn [e] (put! upload-chan e))
                                 }])]
    (dommy/append! (sel1 :#create-removal_button) el)
    ))

(defn connect-to-background-page! []
  (let [background-port (runtime/connect)]
    (post-message! background-port "hello from CONTENT SCRIPT!")
    (run-message-loop! background-port)
    (do-page-analysis! background-port)
    (setup-ui background-port)
    ))

; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (log "CONTENT SCRIPT: init")
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
          csv-data (csv/read-csv (clojure.string/trim file-content))
          ]
      (prn csv-data)
      (recur)))

  (connect-to-background-page!))
