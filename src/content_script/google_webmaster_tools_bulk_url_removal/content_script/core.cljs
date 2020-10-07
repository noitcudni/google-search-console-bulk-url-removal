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

(defn scrape-xhr-data! []
  "grab the xhr injected data and clean up the extra dom"
  []
  (go
    (let [injected-dom (<! (sync-single-node "//div[@id='__interceptedData']"))]
      (dommy/text injected-dom))))

(defn cleanup-xhr-data! []
  (go
    (doseq [n (<! (sync-nodes  "//div[@id='__interceptedData']"))]
      (.remove n))))

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
                           (= url-type "url-only") "Remove this URL only")
        next-button-xpath "//span[contains(text(), 'Temporarily remove URL')]/../../../../../../descendant::span[contains(text(), 'Next')]/../.."]
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
                (loop [next-nodes (nodes (xpath next-button-xpath))
                       iter-cnt 1]
                  (when (->> next-nodes
                             (every? (fn [n]
                                       (= (-> n
                                              js/window.getComputedStyle
                                              (aget "backgroundColor")) "rgba(0, 0, 0, 0.12)"))))
                    (cond (= url-method "remove-url")
                          (do
                            (.click (<! (sync-single-node "//span[contains(text(), 'Clear cached URL')]")))
                            (<! (async/timeout (* iter-cnt 300)))
                            (.click (<! (sync-single-node "//span[contains(text(), 'Temporarily remove URL')]")))
                            (recur (nodes (xpath next-button-xpath)) (inc iter-cnt))
                            )
                          (= url-method "clear-cached")
                          (do (.click (<! (sync-single-node "//span[contains(text(), 'Clear cached URL')]")))
                              (<! (async/timeout (* iter-cnt 300)))
                              (recur (nodes (xpath next-button-xpath)) (inc iter-cnt)))
                          :else
                          ;; trigger skip-error
                          (prn "Need to skip-error due to url-method : " url-method) ;;xxx
                          )
                    ))

                ;; NOTE: there are two next buttons. One on each tab. Ideally, I'll use xpath to distill down to the ONE.
                ;; I can only narrow it down for now. So, just loop through and click on all of them.
                (doseq [n (<! (sync-nodes next-button-xpath))]
                  (.click n))

                ;; Wait for the next dialog
                (<! (sync-single-node "//div[contains(text(), 'URL not in property')]"
                                      "//div[contains(text(), 'Clear cached URL?')]"
                                      "//div[contains(text(), 'Remove URL?')]"
                                      "//div[contains(text(), 'Remove all URLs with this prefix?')]"
                                      "//div[contains(text(), 'Remove entire site?')]"))

                (prn "Yay, the next dialog is here !!! --> " url) ;;xxx
                ;; Check for "URL not in property"
                (if-let [not-in-properity-node (single-node (xpath "//div[contains(text(), 'URL not in property')]"))]
                  ;; Oops, not in the right domain
                  (do
                    (.click (<! (sync-single-node "//span[contains(text(), 'Close')]")))
                    (.click (<! (sync-single-node "//span[contains(text(), 'cancel')]")))
                    (>! ch :not-in-property))

                  ;; NOTE: may encounter
                  ;; 1. Duplicate request
                  ;; 2. Malform URL
                  ;; These show up as a modal dialog. Need to check for them
                  ;; Check for post submit modal dialog
                  (do
                    (<! (cleanup-xhr-data!))
                    (prn "about to click on submit request")
                    (.click (<! (sync-single-node "//span[contains(text(), 'Submit request')]")))
                    (let [xhr-data (<! (scrape-xhr-data!))
                          _ (prn "xhr-data: " (subs xhr-data 0 (min 1024 (count xhr-data))))]
                      (if (clojure.string/includes? (subs xhr-data 0 (min 1024 (count xhr-data))) "SubmitRemovalError")
                        (let [err-ch (sync-single-node  "//div[contains(text(), 'Duplicate request')]"
                                                        "//div[contains(text(), 'Malformed URL')]")
                              _ (<! err-ch)
                              dup-req-node (single-node (xpath "//div[contains(text(), 'Duplicate request')]"))
                              malform-url-node (single-node (xpath "//div[contains(text(), 'Malformed URL')]"))]
                          (cond (not (nil? dup-req-node)) (do
                                                            (.click (<! (sync-single-node "//span[contains(text(), 'Close')]")))
                                                            (>! ch :duplicate-request))
                                (not (nil? malform-url-node)) (do
                                                                (.click (<! (sync-single-node "//span[contains(text(), 'Close')]")))
                                                                (>! ch :malform-url))
                                ))
                        (>! ch :success)
                        ))
                    )))
            ))
    ch))


; -- a message loop ---------------------------------------------------------------------------------------------------------
(defn process-message! [chan message]
  (let [{:keys [type] :as whole-msg} (common/unmarshall message)]
    (prn "CONTENT SCRIPT: process-message!: " whole-msg)
    (cond (= type :done-init-victims) (do
                                        (go
                                          ;; clean up the injected xhr data
                                          (<! (cleanup-xhr-data!))
                                          (post-message! chan (common/marshall {:type :next-victim}))))
          (= type :remove-url) (do (prn "handling :remove-url")
                                   (go
                                     (let [{:keys [victim removal-method url-type]} whole-msg
                                           request-status (<! (exec-new-removal-request victim
                                                                                        removal-method url-type))
                                           ;; NOTE: This timeout is here to prevent a race condition.
                                           ;; For reasons unbeknownst to me, a successful submission can
                                           ;; results in 2 responses. We don't care which one comes back first
                                           ;; so long as one of them does.
                                           ;;
                                           ;; However, the second ajax call may come back later than expected.
                                           ;; Even though we clean up the ejected dom right before clicking on
                                           ;; the submit button. It's entirely possible that the previously successful
                                           ;; submission comes back right after the clean up, resulting in
                                           ;; the extension getting stuck.
                                           ;;
                                           ;; The timeout is here to allow for plenty of time for the second ajax call
                                           ;; to come back.
                                           _ (<! (async/timeout 1500))]
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


(defn ensure-english-setting []
  (let [url-parts (url (.. js/window -location -href))]
    (when-not (= "en" (get-in url-parts [:query "hl"]))
      (js/alert "Bulk URL Removal extension works properly only in English. Press OK to set the language to English.")
      (set! (.. js/window -location -href) (str (assoc-in url-parts [:query "hl"] "en")))
      )))


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
