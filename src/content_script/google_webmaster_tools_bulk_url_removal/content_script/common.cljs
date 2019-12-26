(ns google-webmaster-tools-bulk-url-removal.content-script.common
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [cognitect.transit :as t]
            [cemerick.url :refer [url]]))

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

(defn normalize-url-encoding [fq-url]
  (let [url-parts (cemerick.url/url fq-url)
        normalized-path (-> url-parts
                            :path
                            (subs 1)
                            cemerick.url/url-decode
                            cemerick.url/url-encode
                            )]
    (-> url-parts
        (assoc :path (str "/" normalized-path))
        )))

(defn fq-victim-url [victim-url]
  (let [domain-name (get-in (url (.. js/window -location -href)) [:query "siteUrl"])]
    (-> (cond (clojure.string/starts-with? victim-url "http") victim-url
              (clojure.string/ends-with? victim-url "/") (str (url domain-name victim-url) "/")
              :else (str (url domain-name victim-url)))
        normalize-url-encoding)
    ))
