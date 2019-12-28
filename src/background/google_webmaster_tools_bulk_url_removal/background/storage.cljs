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

(defn store-victims!
  "status: pending, removed, removing"
  [{:keys [global-removal-method data]}]
  (let [local-storage (storage/get-local)]
    (go-loop [[[url optional-removal-method :as curr] & more] data
              idx 0]
      (if (nil? curr)
        (log "DONE storing victims")
        (let [removal-method (cond (contains? #{"PAGE", "PAGE_CACHE", "DIRECTORY"} optional-removal-method) optional-removal-method
                                   (empty? optional-removal-method) global-removal-method
                                   ;; TODO: show an error message in a modal dialog
                                   )
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

(defn update-storage [url & args]
  {:pre [(even? (count args))]}
  (let [kv-pairs (partition 2 args)
        local-storage (storage/get-local)]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage url))]
        (if error
          (error (str "fetching " url ":") error)
          (let [entry (->> (js->clj items) vals first)]
            (storage-area/set local-storage (clj->js {url (->> kv-pairs
                                                               (reduce (fn [accum [k v]]
                                                                         (assoc accum k v))
                                                                       entry))
                                                      }))))))
    ))


(defn current-removal-attempt
  "NOTE: There should only be one item that's undergoing removal.
  Return nil if not found.
  Return URL if found.
  "
  []
  (let [local-storage (storage/get-local)]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage))]
        (->> items
             js->clj
             (filter (fn [[k v]]
                       (= "removing" (get v "status"))
                       ))
             first)
        ))
    ))


(defn fresh-new-victim []
  (let [local-storage (storage/get-local)
        ch (chan)]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage))
            [victim-url victim-entry] (->> (or items '())
                                           js->clj
                                           (sort-by (fn [[_ v]] (get v "idx")))
                                           (filter (fn [[k v]]
                                                     (let [status (get v "status")]
                                                       (= "pending" status))))
                                           first)
            _ (when-not (nil? victim-entry) (<! (update-storage victim-url "status" "removing")))
            victim (<! (current-removal-attempt))]
        (>! ch victim)
        ))
    ch))

(defn next-victim []
  (let [local-storage (storage/get-local)
        ch (chan)]
    (go
      (let [victim (<! (current-removal-attempt))
            victim (if (empty? victim)
                     (<! (fresh-new-victim))
                     victim)
            ]
        (>! ch victim)
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
