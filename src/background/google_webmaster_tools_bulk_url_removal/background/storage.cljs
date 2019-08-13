(ns google-webmaster-tools-bulk-url-removal.background.storage
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! chan take!]]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cljs-time.format :as tf]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-storage-area :as storage-area]
            [chromex.ext.storage :as storage]))

;; https://cljdoc.org/d/binaryage/chromex/0.7.1/api/chromex.protocols.chrome-storage-area
;; (remove this keys)
;; (clear this)

;; TODO: remove me later
#_(defn test-storage! []
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

#_{:url_v  {:method_v_1 {:submit-ts __ :remove-ts __ :status :removed :idx 0}
            :method_v_2 {:submit-ts __ :remove-ts __ :status :pending :idx 1}
            :method_v_3 {:submit-ts __ :remove-ts __ :status :pending :idx 2}
            }}

;; (tc/to-date (tc/from-long (tc/to-long (t/now))))
;; [cljs-time.format :as tf] tf/formatter
;; {"https://polymorphiclabs.io/tags-output/mobile%20app/" {"PAGE_CACHE" {"idx" 0, "remove-ts" nil, "status" "pending", "submit-ts" 1565375547359}}, "https://polymorphiclabs.io/tags-output/re-natal/" {"PAGE_CACHE" {"idx" 1, "remove-ts" nil, "status" "pending", "submit-ts" 1565375547363}}, "key1" "string", "key2" [1 2 3], "key3" true, "key4" nil, "key5" {"foo" "bar"}}


(defn store-victims!
  "status: pending, removed, removing"
  [{:keys [global-removal-method data]}]
  (let [local-storage (storage/get-local)]
    (go-loop [[[url optional-removal-method :as curr] & more] data
              idx 0]
      (if (nil? curr)
        (log "DONE storing victims")
        (let [removal-method (or optional-removal-method global-removal-method)
              [[items] error] (<! (storage-area/get local-storage url))]
          (if error
            (error (str "fetching " url ":") error)
            (do (log "setting url: " url " | method: " removal-method)
                (storage-area/set local-storage (clj->js {url {"submit-ts" (tc/to-long (t/now))
                                                  "remove-ts" nil
                                                  "removal-method" removal-method
                                                  "status" "pending"
                                                  "idx" idx}
                                             }))))
          (recur more (inc idx)))
        ))
    ))

(defn update-storage [url k v]
  (let [local-storage (storage/get-local)]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage url))]
        (if error
          (error (str "fetching " url ":") error)
          (let [entry (->> (js->clj items) vals first)]
            (storage-area/set local-storage (clj->js {url (assoc entry k v)}))))))))




(defn current-removal-attempt
  "NOTE: There should only be one item that's undergoing removal.
  Return nil if not found.
  Return URL if found.
  "
  []
  (let [local-storage (storage/get-local)
        ch (chan)
        ]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage))]
        (>! ch
            (or (->> items
                     js->clj
                     (filter (fn [[k v]]
                               (= "removing" (get v "status"))
                               ))
                     first)
                   {}))
        ))
    ch))

;; (defn pending-victim-cnt []
;;   (let [local-storage (storage/get-local)
;;         ch (chan)]
;;     (go
;;       (let [[[items] error] (<! (get local-storage))]
;;         (>! ch (->> (or items '())
;;                     js->clj
;;                     (filter (fn [[k v]]
;;                               (let [status (get v "status")]
;;                                 (= "pending" status))))
;;                     count))
;;         ))
;;     ch))

(defn next-victim []
  (let [local-storage (storage/get-local)
        ch (chan)
        _ (prn "calling next-victim")
        ]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage))]
        (>! ch (->> (or items '())
                    js->clj
                    (filter (fn [[k v]]
                              (let [status (get v "status")]
                                (= "pending" status))))
                    first))
        ))
    ch))


(defn clear-victims! []
  (let [local-storage (storage/get-local)]
    (storage-area/clear local-storage)))

(defn print-victims []
  (let [local-storage (storage/get-local)]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage))]
           (prn (js->clj items))
        ))
    ))
