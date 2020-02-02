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
            [domina :refer [single-node nodes]]
            [domina.xpath :refer [xpath]]
            [domina.events :refer [dispatch!]]
            ))

; -- a message loop ---------------------------------------------------------------------------------------------------------

(defn process-message! [chan message]
  (let [{:keys [type] :as whole-msg} (common/unmarshall message)]
    (prn "CONTENT SCRIPT: process-message!: " whole-msg)
    (cond (= type :done-init-victims) (post-message! chan (common/marshall {:type :next-victim}))
          (= type :remove-url) (do (prn "handling :remove-url")
                                   (dommy/set-value! (sel1 "input[name=\"urlt\"]") (:victim whole-msg))
                                   (.click (sel1 "input[name=\"urlt.submitButton\"]"))
                                   )
          )
    ))


; -- custom ui components  ------------------------------------------------------------------------------------------------
(def upload-chan (chan 1 (map (fn [e]
                                (let [target (.-currentTarget e)
                                      file (-> target .-files (aget 0))]
                                  (set! (.-value target) "")
                                  file
                                  )))))

(def read-chan (chan 1 (map #(-> % .-target .-result js->clj))))


(defn ensure-english-setting []
  (let [url-parts (url (.. js/window -location -href))]
    (when-not (= "en" (get-in url-parts [:query "hl"]))
      (js/alert "Bulk URL Removal extension works properly only in English. Press OK to set the language to English.")
      (set! (.. js/window -location -href) (str (assoc-in url-parts [:query "hl"] "en")))
      )))

(defn setup-ui [background-port]
  (let [file-input-el (hipo/create [:div
                                    [:input {:id "fileInput" :type "file"
                                             :on-change (fn [e] (put! upload-chan e))
                                             }]])
        clear-db-btn-el (hipo/create [:div
                                      [:button {:type "button"
                                                :on-click (fn [_]
                                                            (log "clear victims from local storage.") ;;xxx
                                                            (clear-victims!)
                                                            )}
                                       "Clear local storage"]])
        print-db-btn-el (hipo/create [:div
                                      [:button {:type "button"
                                                :on-click (fn [_]
                                                            (log "print db btn clicked!")
                                                            (print-victims)
                                                            (go
                                                              (let [_ (prn "did we get here?")
                                                                    bad-victims (<! (get-bad-victims))]
                                                                (prn "bad-victims: "  bad-victims)
                                                                ))
                                                            )

                                                }
                                       "View local storage"
                                       ]])
        br-el (hipo/create [:br])
        target-el (single-node (xpath "//div[contains(text(), 'Need to urgently remove content from Google Search?')]"))
        ]

    ;; TODO style these buttons later
    (dommy/append! target-el file-input-el)
    (dommy/append! target-el clear-db-btn-el)
    (dommy/append! target-el print-db-btn-el)
    ))

(defn setup-continue-ui [background-port]
  (let [continue-button-el (hipo/create [:div [:button {:type "button"
                                                        :on-click (fn []
                                                                    (post-message! background-port (common/marshall {:type :next-victim}))
                                                                    )}
                                               "Continue"
                                               ]])]
    (dommy/append! (sel1 :#create-removal_button) continue-button-el)
    ))


(defn most-recent-table-grid-entry []
  (when-let [el (sel1 "tr.first")]
    (-> el
        dommy/text
        clojure.string/trim
        (clojure.string/split #"\n")
        first
        common/normalize-url-encoding
        )))


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

(defn update-removal-status
  "Grab the most recent table entry and compare that against what we think the most recent url instead of
  grabbing the url from the status message."
  []
  (go
    (when-let [most-recent-url (most-recent-table-grid-entry)]
      (prn "update-removal-status -> most-recent-url: " most-recent-url)
      (when-let [[curr-removal-url _] (<! (current-removal-attempt))]
        (when (= (common/fq-victim-url curr-removal-url) most-recent-url)
          (<! (update-storage curr-removal-url
                              "status" "removed"
                              "remove-ts" (tc/to-long (t/now))
                              )))
        ))))

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

;; default to Temporarily remove and Remove this URL only
(defn exec-new-removal-request
  "url-method: :remove-url vs :clear-cached
  url-type: :url-only vs :prefix"
  [url url-method url-type]
  (let [url-type-str (if (= url-type :prefix)
                           "Remove all URLs with this prefix"
                           "Remove this URL only")]

   (go (.click (single-node (xpath "//span[contains(text(), 'New Request')]")))

       (<! (async/timeout 700)) ;; wait for the modal dialog to show
       ;; Who cares? Click on all the radiobuttons
       (doseq [n (nodes (xpath (str "//label[contains(text(), '" url-type-str "')]/div")))]
         (.click n))

       (doseq [n (nodes (xpath "//input[@placeholder='Enter URL']"))]
         (do
           (.click n)
           (domina/set-value! n url)))

       ;; NOTE: Need to click one of the tabs to get next to show
       (if (= url-method :removal-url)
         (do
           (.click (single-node (xpath "//span[contains(text(), 'Clear cached URL')]")))
           (<! (async/timeout 700))
           (.click (single-node (xpath "//span[contains(text(), 'Temporarily remove URL')]"))))

         (.click (single-node (xpath "//span[contains(text(), 'Clear cached URL')]"))))


       (<! (async/timeout 700))
       (.click (single-node (xpath "//span[contains(text(), 'Next')]")))
       (<! (async/timeout 700))
       (.click (single-node (xpath "//span[contains(text(), 'Submit request')]")))
       )))


; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (let [_ (log "CONTENT SCRIPT: init")
        background-port (runtime/connect)
        _ (prn "background-port: " background-port)]

    ;; handle onload
    (go-loop []
      (let [reader (js/FileReader.)
            file (<! upload-chan)]
        (set! (.-onload reader) #(put! read-chan %))
        (.readAsText reader file)
        (recur)))

    ;; handle read the file
    (go-loop []
      (let [file-content (<! read-chan)
            _ (prn "file-content: " (clojure.string/trim file-content))
            csv-data (->> (csv/read-csv (clojure.string/trim file-content))
                          ;; trim off random whitespaces
                          (map (fn [[url method]]
                                 (->> [(clojure.string/trim url)
                                       (when method (clojure.string/trim method))]
                                      (filter (complement nil?))
                                      ))))]
        (prn "about to call :init-victims")
        (post-message! background-port (common/marshall {:type :init-victims
                                                         :data csv-data
                                                         }))
        (recur)))

    ;;;; new version
    (go
      (ensure-english-setting)
      (setup-ui background-port)
      (common/connect-to-background-page! background-port process-message!)

      )

    ;(exec-new-removal-request "https://polymorphiclabs.io/en-us/blah/blah/" :clear-cached :prefix)
    ;; function my_click(el) {
    ;;                        var evt=document.createEvent("MouseEvent");
    ;;                        evt.initMouseEvent("click", true, true, window, 1, 0, 0, 0, 0, undefined, undefined, undefined, undefined, 0, null);
    ;;                        el.dispatchEvent(evt);
    ;;                        }


    ;; (.click (single-node (xpath "//span[contains(text(), 'Clear cached URL'")))


    ;; "Temporarily remove URL"  ;;Tab
    ;; "Clear cached URL" ;;Tab

    ;;;;; old version
    ;; Ask for the next victim if there's no failure.
    ;; If current-removal-attempt returns nil, that means
    ;; that there's no outstanding failure.
    #_(go
      (<! (async/timeout 1500)) ;; wait a bit for the ui to update
      (<! (update-removal-status))
      (<! (skip-has-already-been-removed-request))
      (ensure-english-setting)
      (setup-ui background-port)
      (common/connect-to-background-page! background-port process-message!)

      (let [_ (prn "Inside go block.") ;;xxx
            curr-removal (<! (current-removal-attempt))
            _ (prn "curr-removal: " curr-removal) ;;xxx
            outstanding-failed-attempt? (->> curr-removal nil? not)]
        (if outstanding-failed-attempt?
          (setup-continue-ui background-port) ;; pause since we have an outstanding failure.
          (post-message! background-port (common/marshall {:type :next-victim}))
          )))
    ))
