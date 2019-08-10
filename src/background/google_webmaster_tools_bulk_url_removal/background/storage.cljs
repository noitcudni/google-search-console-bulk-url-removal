(ns google-webmaster-tools-bulk-url-removal.background.storage
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! chan take!]]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cljs-time.format :as tf]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-storage-area :refer [get set]]
            [chromex.ext.storage :as storage]))

;; https://cljdoc.org/d/binaryage/chromex/0.7.1/api/chromex.protocols.chrome-storage-area
;; (remove this keys)
;; (clear this)

(defn test-storage! []
  (let [local-storage (storage/get-local)
        _ (set local-storage #js {"key1" "string"
                                  "key2" #js [1 2 3]
                                  "key3" true
                                  "key4" nil})
        _ (set local-storage #js {"key5" #js {"foo" "bar"}})
        ]
    (go
      (let [[[items] error] (<! (get local-storage))]
        (if error
          (error "fetch all error:" error)
          (log "fetch all:" items))))
    (go
      (let [[[items] error] (<! (get local-storage "key1"))]
        (if error
          (error "fetch key1 error:" error)
          (log "fetch key1:" items))))
    (go
      (let [[[items] error] (<! (get local-storage #js ["key2" "key3"]))]
        (if error
          (error "fetch key2 and key3 error:" error)
          (log "fetch key2 and key3:" items))))
    (go
      (let [[[items] error] (<! (get local-storage "fake-key"))]
        (if error
          (error "fetch fake-key error:" error)
          (log "fetch fake-key:" items)))
      )))

#_{:url_v  {:method_v_1 {:submit-ts __ :remove-ts __ :status :done :idx 0}
            :method_v_2 {:submit-ts __ :remove-ts __ :status :pending :idx 1}
            }}

;; (tc/to-date (tc/from-long (tc/to-long (t/now))))
;; [cljs-time.format :as tf] tf/formatter
;; TODO: how do I clear localstorage?

(defn store-victims! [{:keys [global-removal-method data]}]
  (let [local-storage (storage/get-local)]
    (go-loop [[[url optional-removal-method :as curr] & more] data
              idx 0]
      (if (nil? curr)
        (log "DONE storing victims")
        (let [removal-method (or optional-removal-method global-removal-method)
              [[items] error] (<! (get local-storage url))]
          (if error
            (error (str "fetching " url ":") error)
            (let [entry (->> (js->clj items) vals first)
                  _ (prn "entry: " entry)]
              (when-not (contains? entry removal-method)
                (log "setting url: " url " | method: " removal-method) ;;xxx
                (set local-storage (clj->js {url (merge
                                                  {removal-method {"submit-ts" (tc/to-long (t/now))
                                                                   "remove-ts" nil
                                                                   "status" "pending"
                                                                   "idx" idx}}
                                                  entry
                                                  )}))
                )))
          (recur more (inc idx)))
        ))
    ))

(defn clear-victims! []
  (let [local-storage (storage/get-local)]
    (clear local-storage)))

(defn print-victims []
  (let [local-storage (storage/get-local)]
    (go
      (let [[[items] error] (<! (get local-storage url))]
           (prn (js->clj items))
        ))
    ))
