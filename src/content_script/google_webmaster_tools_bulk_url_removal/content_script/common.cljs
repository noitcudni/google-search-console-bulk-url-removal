(ns google-webmaster-tools-bulk-url-removal.content-script.common
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [cognitect.transit :as t]))

(defn run-message-loop! [message-channel message-handler!]
  (log "CONTENT SCRIPT: starting message loop...")
  (go-loop []
    (when-some [message (<! message-channel)]
      ;; (process-message! message-channel message)
      (message-handler! message-channel message)
      (recur))
    (log "CONTENT SCRIPT: leaving message loop")))


(defn connect-to-background-page! [background-port message-handler!]
  ;; (post-message! background-port "hello from CONTENT SCRIPT!")
  (run-message-loop! background-port message-handler!))

(defn marshall [edn-msg]
  (let [w (t/writer :json)]
    (t/write w edn-msg)))

(defn unmarshall [msg-str]
  (let [r (t/reader :json)]
    (t/read r msg-str)))
