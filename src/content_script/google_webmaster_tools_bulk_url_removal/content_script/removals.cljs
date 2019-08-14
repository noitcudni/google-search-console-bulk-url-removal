(ns google-webmaster-tools-bulk-url-removal.content-script.removals
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan]]
            [hipo.core :as hipo]
            [dommy.core :refer-macros [sel sel1] :as dommy]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
            [google-webmaster-tools-bulk-url-removal.background.storage :refer [clear-victims! print-victims current-removal-attempt]]
            )
  )

;; (defn process-message! [chan message]
;;   ;; $("select[name='removalmethod']").val(msg.removalMethod);
;;   ;; $submitBtn.trigger('click');
;;   )

(defn setup-ui [background-port]
  (let [_ (prn "calling setup-ui") ;; xxx
        clear-db-btn-el (hipo/create [:div [:button {:type "button"
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
    ;; (common/connect-to-background-page! background-port process-message!)

    (go
      (let [[victim-url victim-entry :as curr-removal] (<! (current-removal-attempt))
            _ (prn "Inside removals' go block: " curr-removal)
            victim-url-from-ui (-> (dommy/text (sel1 ".url-to-be-removed"))
                                   (clojure.string/split #",")
                                   first
                                   clojure.string/trim
                                   (clojure.string/replace #" " "%20"))
            ]

        ;; TODO check to make sure that it matches the ui
        (when (= victim-url victim-url-from-ui)
          (dommy/set-value! (sel1 "select[name=\"removalmethod\"]") (get victim-entry "removal-method"))
          (.click (sel1 "input[name=\"next\"]")))
        ))
    ))
