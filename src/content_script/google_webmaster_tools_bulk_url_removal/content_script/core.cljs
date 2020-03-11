(ns google-webmaster-tools-bulk-url-removal.content-script.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan] :as async]
            [hipo.core :as hipo]
            [dommy.core :refer-macros [sel sel1] :as dommy]
            [testdouble.cljs.csv :as csv]
            ;; [cognitect.transit :as t]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [google-webmaster-tools-bulk-url-removal.content-script.common :as common]
            [google-webmaster-tools-bulk-url-removal.background.storage :refer [clear-victims! print-victims update-storage
                                                                                current-removal-attempt get-bad-victims]]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cemerick.url :refer [url]]
            [domina :refer [single-node nodes style styles]]
            [domina.xpath :refer [xpath]]
            [domina.events :refer [dispatch!]]
            ))

(defn sync-node-helper
  "This is unfortunate. alts! doens't close other channels"
  [dom-fn & xpath-strs]
  (go-loop []
    (let [n (->> xpath-strs
                 (map (fn [xpath-str]
                        (dom-fn (xpath xpath-str))
                        ))
                 (filter #(some? %))
                 first)]
      (if (nil? n)
        (do (<! (async/timeout 300))
            (recur))
        n)
      )))

(def sync-single-node (partial sync-node-helper single-node))
(def sync-nodes (partial sync-node-helper nodes))

;; default to Temporarily remove and Remove this URL only
(defn exec-new-removal-request
  "url-method: :remove-url vs :clear-cached
  url-type: :url-only vs :prefix
  Possible return value in a channel
  1. :not-in-property
  2. :duplicate-request
  3. :malform-url
  4. :success
  "
  [url url-method url-type]
  (let [ch (chan)
        url-type-str (cond (= url-type "prefix") "Remove all URLs with this prefix"
                           (= url-type "url-only") "Remove this URL only"
                           )]
    (go
      (cond (and (not= url-method "remove-url") (not= url-method "clear-cached"))
            (>! ch :erroneous-url-method)
            (and (not= url-type "url-only") (not= url-type "prefix"))
            (>! ch :erroneous-url-type)
            :else
            (do #_(.click (single-node (xpath "//span[contains(text(), 'New Request')]")))
                (.click (<! (sync-single-node "//span[contains(text(), 'New Request')]")))

                ;; wait for the modal dialog to show
                (<! (sync-single-node "//div[@aria-label='New Request']"))

                ;; Who cares? Click on all the radiobuttons
                (doseq [n (<! (sync-nodes (str "//label[contains(text(), '" url-type-str "')]/div")))]
                  (.click n))

                (doseq [n (<! (sync-nodes "//input[@placeholder='Enter URL']"))]
                  (do
                    (.click n)
                    (domina/set-value! n url)))

                ;; NOTE: Need to click one of the tabs to get next to show
                ;; Increment the wait time in between clicking on the `Clear cached URL` and the `Temporarily remove URL` tabs.
                ;; Don't stop until the next button is clickable
                (loop [next-node (single-node (xpath "//span[contains(text(), 'Next')]/../.."))
                       iter-cnt 1]
                  (when (= (-> next-node
                               js/window.getComputedStyle
                               (aget "backgroundColor")) "rgba(0, 0, 0, 0.12)")
                    (cond (= url-method "remove-url")
                          (do
                            (.click (<! (sync-single-node "//span[contains(text(), 'Clear cached URL')]")))
                            (<! (async/timeout (* iter-cnt 300)))
                            (.click (<! (sync-single-node "//span[contains(text(), 'Temporarily remove URL')]")))
                            (recur (single-node (xpath "//span[contains(text(), 'Next')]/../..")) (inc iter-cnt))
                            )
                          (= url-method "clear-cached")
                          (do (.click (<! (sync-single-node "//span[contains(text(), 'Clear cached URL')]")))
                              (<! (async/timeout (* iter-cnt 300)))
                              (recur (single-node (xpath "//span[contains(text(), 'Next')]/../..")) (inc iter-cnt)))
                          :else
                          ;; trigger skip-error
                          (prn "Need to skip-error dude to url-method : " url-method) ;;xxx
                          )
                    ))

                (.click (<! (sync-single-node "//span[contains(text(), 'Next')]")))

                ;; Wait for the next dialog
                (<! (sync-single-node "//div[contains(text(), 'URL not in property')]"
                                      "//div[contains(text(), 'Clear cached URL?')]"
                                      "//div[contains(text(), 'Remove URL?')]"
                                      "//div[contains(text(), 'Remove all URLs with this prefix?')]"))

                (prn "Yay, the next dialog is here !!!") ;;xxx
                ;; Check for "URL not in property"
                (if-let [not-in-properity-node (single-node (xpath "//div[contains(text(), 'URL not in property')]"))]
                  ;; Oops, not in the right domain
                  (do
                    (.click (<! (sync-single-node "//span[contains(text(), 'Close')]")))
                    (.click (<! (sync-single-node "//span[contains(text(), 'cancel')]")))
                    (>! ch :not-in-property))

                  (do
                    (prn "about to click on submit request")
                    (.click (<! (sync-single-node "//span[contains(text(), 'Submit request')]")))
                      ;; TODO Can we refactor and get rid of this timeout?
                      ;; Instead relying on time out we can try to see if the first entry in the table matches the input
                      ;; $x("//table//*[contains(text(), 'Requested')]/../../../../tbody/tr")
                      (<! (async/timeout 1400))
                      ;; NOTE: may encounter
                      ;; 1. Duplicate request
                      ;; 2. Malform URL
                      ;; These show up as a modal dialog. Need to check for them
                      ;; Check for post submit modal dialog
                      (let [dup-req-node (single-node (xpath "//div[contains(text(), 'Duplicate request')]"))
                            malform-url-node (single-node (xpath "//div[contains(text(), 'Malformed URL')]"))]
                        (cond (not (nil? dup-req-node)) (do
                                                          (.click (<! (sync-single-node "//span[contains(text(), 'Close')]")))
                                                          (>! ch :duplicate-request))
                              (not (nil? malform-url-node)) (do
                                                              (.click (<! (sync-single-node "//span[contains(text(), 'Close')]")))
                                                              (>! ch :malform-url))
                              :else (>! ch :success)
                              )
                        ))))


            ))

    ch))

; -- a message loop ---------------------------------------------------------------------------------------------------------
(defn process-message! [chan message]
  (let [{:keys [type] :as whole-msg} (common/unmarshall message)]
    (prn "CONTENT SCRIPT: process-message!: " whole-msg)
    (cond (= type :done-init-victims) (post-message! chan (common/marshall {:type :next-victim}))
          (= type :remove-url) (do (prn "handling :remove-url")
                                   (go
                                     (let [{:keys [victim removal-method url-type]} whole-msg
                                           request-status (<! (exec-new-removal-request victim
                                                                                        removal-method url-type))
                                           _ (<! (async/timeout 1200))]
                                       (prn "request-status: " request-status)
                                       (if (or (= :success request-status) (= :duplicate-request request-status))
                                         (post-message! chan (common/marshall {:type :success
                                                                               :url victim}))
                                         (post-message! chan (common/marshall {:type :skip-error
                                                                               :reason request-status
                                                                               :url victim
                                                                               })))
                                       )))
          (= type :done) (js/alert "DONE with bulk url removals!")
          )
    ))


; -- custom ui components  ------------------------------------------------------------------------------------------------



(defn ensure-english-setting []
  (let [url-parts (url (.. js/window -location -href))]
    (when-not (= "en" (get-in url-parts [:query "hl"]))
      (js/alert "Bulk URL Removal extension works properly only in English. Press OK to set the language to English.")
      (set! (.. js/window -location -href) (str (assoc-in url-parts [:query "hl"] "en")))
      )))



;; TODO: to be deprecated?
(defn setup-continue-ui [background-port]
  (let [continue-button-el (hipo/create [:div [:button {:type "button"
                                                        :on-click (fn []
                                                                    (post-message! background-port (common/marshall {:type :next-victim}))
                                                                    )}
                                               "Continue"
                                               ]])]
    (dommy/append! (sel1 :#create-removal_button) continue-button-el)
    ))





;; TODO: to be deprecated
(defn skip-has-already-been-removed-request
  "If the removal request has previously been made, update its status as removed"
  []
  ;; <span class="status-message-text>"
  ;; A removal request for this URL has already been made.
  ;; TODO: work in progress
  (go
    (if-let [r (when-let [el (sel1 "span.status-message-text")]
                 (when-let [[curr-removal-url _] (<! (current-removal-attempt))]
                   (when (= (-> el
                                dommy/text
                                clojure.string/trim) "A removal request for this URL has already been made.")
                     ;; NOTE: The removal timestamp is not accurate.
                     ;; It has been removed previously. Just need to update it so that it'll move along
                     (<! (update-storage curr-removal-url
                                         "status" "removed"
                                         "remove-ts" (tc/to-long (t/now))
                                         ))
                     )))]
      r
      "DO Nothing" ;; can't put nil on a channel
      )))



;; has to be used inside a go block
#_(defmacro with-wait [[action [_ [_ p] :as single-node-sexp] ]]
  `(let [path# ~p]
     (loop [n# (~'single-node (~'xpath path#))]
       (if (nil? n#)
         (do
           (<! (cljs.core.async/timeout 100))
           (recur (~'single-node (~'xpath path#))))
         (do
           (~'prn "n#: ")
           (.click n#))
         ))))


; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (let [_ (log "CONTENT SCRIPT: init")
        background-port (runtime/connect)
        _ (prn "single-node: "(single-node (xpath "//span[contains(text(), 'Hello world')]"))) ;;xxx
        _ (prn "nodes: " (nodes (xpath "//label[contains(text(), 'hello')]/div"))) ;;xxx
        ]
    (go
      (ensure-english-setting)
      (common/connect-to-background-page! background-port process-message!)
      )
    ))
