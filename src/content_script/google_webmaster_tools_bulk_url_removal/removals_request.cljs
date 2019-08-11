(ns content-script.google-webmaster-tools-bulk-url-removal.removals-request
  (:require-macros [chromex.support :refer [runonce]])
  ;; TODO point it to something else
  (:require [google-webmaster-tools-bulk-url-removal.content-script.core :as core]))

(runonce
 (core/init!))
