(ns google-webmaster-tools-bulk-url-removal.popup.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [<!]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
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

; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (log "POPUP: init")
  (connect-to-background-page!))
