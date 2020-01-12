(ns google-webmaster-tools-bulk-url-removal.content-script.removals
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan]]
            [goog.string :as gstring]
            [hipo.core :as hipo]
            [dommy.core :refer-macros [sel sel1] :as dommy]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
            [google-webmaster-tools-bulk-url-removal.background.storage :refer [clear-victims! print-victims current-removal-attempt]]))


(defn setup-ui [background-port]
  (let [clear-db-btn-el (hipo/create [:div [:button {:type "button"
                                                     :on-click (fn [_]
                                                                 (log "clear victims from local storage.") ;;xxx
                                                                 (clear-victims!)
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
    (dommy/append! (sel1 :#confirm-blocked-access) clear-db-btn-el)
    (dommy/append! (sel1 :#confirm-blocked-access) print-db-btn-el)
    ))

(defn init! []
  (let [background-port (runtime/connect)]
    (setup-ui background-port)

    (go
      (let [[victim-url victim-entry :as curr-removal] (<! (current-removal-attempt))]
        (try
          (let [_ (prn "Inside removals' go block: " curr-removal)
                victim-url (common/fq-victim-url victim-url)
                victim-url-from-ui (-> (dommy/html (sel1 ".url-to-be-removed"))
                                       (clojure.string/split #"<strong>")
                                       first
                                       clojure.string/trim
                                       gstring/unescapeEntities
                                       ((fn [x]
                                          (if (= (last x) \,)
                                            (subs x 0 (dec (count x)))
                                            x)))
                                       common/normalize-url-encoding)
                _ (prn "victim-url-from-ui: " victim-url-from-ui)
                _ (prn "victim-url:         " victim-url)
                _ (prn "victim-url-str: " (str victim-url))
                _ (prn "victim-entry: "  victim-entry)
                ]

            ;; check to make sure that it matches the ui
            ;; 1. log if victim-url and victim-rul-from-ui are different, inc error count,  and skip
            ;; 2. log if removal-method is invalid, inc error count, and skip
            ;; There's a cancel button input[name="cancel"]
            (cond (not= victim-url victim-url-from-ui)
                  ;; TODO: can I get to the row number?
                  (do (post-message! background-port (common/marshall
                                                      {:type :skip-error
                                                       :reason :weird-characters
                                                       :url (str victim-url)}))
                      (.click (sel1 "input[name=\"cancel\"]")))
                  (not (contains? #{"PAGE" "PAGE_CACHE" "DIRECTORY"} (get victim-entry "removal-method")))
                  (do (post-message! background-port (common/marshall
                                                      {:type :skip-error
                                                       :reason :invalid-removal-method
                                                       :url (str victim-url)}))
                      (.click (sel1 "input[name=\"cancel\"]")))
                  :else ;; happy case
                  (do (dommy/set-value! (sel1 "select[name=\"removalmethod\"]") (get victim-entry "removal-method"))
                      (.click (sel1 "input[name=\"next\"]"))))
            )
          (catch js/Object e
            (post-message! background-port (common/marshall
                                            {:type :skip-error
                                             :reason :unknown
                                             :url victim-url}))
            ))))
    ))
