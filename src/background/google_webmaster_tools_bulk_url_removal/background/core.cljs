(ns google-webmaster-tools-bulk-url-removal.background.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [goog.string :as gstring]
            [goog.string.format]
            [clojure.string :as string]
            [cljs.core.async :refer [<! chan]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]
            [chromex.protocols.chrome-port :refer [on-disconnect! post-message! get-sender]]
            [chromex.ext.tabs :as tabs]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.web-request :as web-request]
            [chromex.ext.windows :as cwin :refer-macros [create]]
            [cljs-time.core :as tt]
            [cljs-time.coerce :as tc]
            [cognitect.transit :as t]
            [chromex.ext.browser-action :refer-macros [set-badge-text set-badge-background-color]]
            [google-webmaster-tools-bulk-url-removal.background.storage :refer [clear-victims! store-victims! update-storage next-victim *DONE-FLAG* get-bad-victims]]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
            ))


(def ^:export github-source-url "This project is from https://github.com/noitcudni/google-webmaster-tools-bulk-url-removal")
(def clients (atom []))
(def my-status (atom :done))

; -- clients manipulation ---------------------------------------------------------------------------------------------------

(defn add-client! [client]
  (prn "BACKGROUND: client connected: " (get-sender client))
  (on-disconnect! client (fn []
                           ;; https://github.com/binaryage/chromex/blob/master/src/lib/chromex/protocols/chrome_port.cljs
                           (prn "on disconnect callback !!!")
                           ;; cleanup
                           (swap! clients (fn [curr c] (->> curr (remove #(= % c)))) client)))
  (swap! clients conj client))

(defn remove-client! [client]
  (prn "BACKGROUND: client disconnected" (get-sender client))
  (let [remove-item (fn [coll item] (remove #(identical? item %) coll))]
    (swap! clients remove-item client)))


(defn popup-predicate [client]
  (re-find #"popup.html" (-> client
                             get-sender
                             js->clj
                             (get "url"))))

(defn get-popup-client []
  (->> @clients
       (filter popup-predicate)
       first ;;this should only be one popup
       ))

(defn get-content-client []
  (->> @clients
       (filter (complement popup-predicate))
       first ;;this should only be one popup
       ))

; -- client event loop ------------------------------------------------------------------------------------------------------
#_{:url_v  {:method_v_1 {:submit-ts __ :remove-ts __ :status :done}
            :method_v_2 {:submit-ts __ :remove-ts __ :status :pending}
            }}

(defn fetch-next-victim [client]
  (go
    (let [[victim-url victim-entry] (<! (next-victim))
          _ (prn "BACKGROUND: victim-url: " victim-url)
          _ (prn "BACKGROUND: victim-entry: " victim-entry)]
      (cond (and (= victim-url "poison-pill") (= (get victim-entry "removal-method") *DONE-FLAG*))
            (do (prn "DONE!!!")
                (reset! my-status :done)
                (post-message! (get-popup-client)
                               (common/marshall {:type :done}))
                (post-message! client
                               (common/marshall {:type :done})))

            (and victim-url victim-entry)
            (post-message! client
                           (common/marshall {:type :remove-url
                                             :victim victim-url
                                             :removal-method (get victim-entry "removal-method")
                                             :url-type (get victim-entry "url-type")
                                             })))
      )))

(defn run-client-message-loop! [client]
  (log "BACKGROUND: starting event loop for client:" (get-sender client))
  (go-loop []
    (when-some [message (<! client)]
      (log "BACKGROUND: got client message:" message "from" (get-sender client))
      (let [{:keys [type] :as whole-edn} (common/unmarshall message)]
        (cond (= type :init-victims) (do
                                       (prn "inside :init-victims --  whole-edn: " whole-edn)
                                       (prn "content-client: " (get-content-client))
                                       ;; clean up errors from the previous run
                                       (clear-victims!)
                                       (set-badge-text #js{"text" ""})
                                       (reset! my-status :running)
                                       (<! (store-victims! whole-edn))
                                       (post-message! (get-content-client) (common/marshall {:type :done-init-victims})))
              (= type :next-victim) (<! (fetch-next-victim client))
              (= type :success) (go
                                  (prn "handle success!!! : " whole-edn) ;;xxx
                                  (let [{:keys [url]} whole-edn]
                                    (<! (update-storage url
                                                        "status" "removed"
                                                        "remove-ts" (tc/to-long (tt/now))
                                                        ))
                                    (<! (fetch-next-victim client))

                                    ))

              (= type :skip-error) (do
                                     (prn "inside :skip-error:" whole-edn)
                                     ;; NOTE: Does someone else need to fire off a next victim event?
                                     ;; No, in removals.cljs, after firing off :skip-error message,
                                     ;; it clicks on cancel right away. This brings the page back to the
                                     ;; main page, which triggers another :next-victim event.

                                     ;; NOTE: ^^ That's not the case any longer in the new version. There's no page refresh
                                     (go
                                       (let [{:keys [url reason]} whole-edn
                                             error-entry {:url url :reason reason}
                                             popup-client (get-popup-client)
                                             updated-error-entry (<! (update-storage url
                                                                                     "status" "error"
                                                                                     "error-reason" reason))
                                             error-cnt (->> (<! (get-bad-victims))
                                                            count
                                                            str)
                                             _ (prn "updated-error-entry: " updated-error-entry) ;;xxx
                                             _ (prn "error-cnt: " error-cnt) ;;xxx
                                             ]
                                         (set-badge-text (clj->js {"text" error-cnt}))
                                         (set-badge-background-color #js{"color" "#F00"})
                                         (when popup-client
                                           (prn "sending popup-client " (common/marshall
                                                                         {:type :new-error :error updated-error-entry}))
                                           (post-message! popup-client (common/marshall
                                                                        {:type :new-error :error updated-error-entry})))
                                         (<! (fetch-next-victim client))
                                         )))

              (= type :fetch-initial-errors) (go
                                               (let [_ (prn "inside :fetch-initial-errors: ")
                                                     bad-victims (<! (get-bad-victims))]
                                                 (post-message! client (common/marshall {:type :init-errors
                                                                                         :bad-victims bad-victims}))))
              ))
      (recur))
    (log "BACKGROUND: leaving event loop for client:" (get-sender client))
    (remove-client! client)))

; -- event handlers ---------------------------------------------------------------------------------------------------------

(defn handle-client-connection! [client]
  (add-client! client)
  (prn ">> number of client: " (count @clients)) ;;xxx

  ;; (post-message! client "hello from BACKGROUND PAGE!")
  (run-client-message-loop! client))

;; (defn tell-clients-about-new-tab! []
;;   (doseq [client @clients]
;;     (post-message! client "a new tab was created")))

; -- main event loop --------------------------------------------------------------------------------------------------------
(defn process-chrome-event [event-num event]
  (log (gstring/format "BACKGROUND: got chrome event (%05d)" event-num) event)
  (let [[event-id event-args] event]
    (case event-id
      ::runtime/on-connect (apply handle-client-connection! event-args)
      ;; ::tabs/on-created (tell-clients-about-new-tab!)
      )))

(defn run-chrome-event-loop! [chrome-event-channel]
  (log "BACKGROUND: starting main event loop...")
  (go-loop [event-num 1]
    (when-some [event (<! chrome-event-channel)]
      (process-chrome-event event-num event)
      (recur (inc event-num)))
    (log "BACKGROUND: leaving main event loop")))

(defn boot-chrome-event-loop! []
  (let [chrome-event-channel (make-chrome-event-channel (chan))]
    ;; (tabs/tap-all-events chrome-event-channel)
    (runtime/tap-all-events chrome-event-channel)
    (run-chrome-event-loop! chrome-event-channel)))

; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (log "BACKGROUND: init")
  ;; (let [ch (cwin/create (clj->js {:type "popup"
  ;;                                 ;; :left 50 :top 50
  ;;                                 :width 100 :height 100}))]
  ;;   (go
  ;;     (let [win (<! ch)]
  ;;       (prn "win: " win)
  ;;       )))
  (boot-chrome-event-loop!))
