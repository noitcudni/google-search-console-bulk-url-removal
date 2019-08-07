(ns google-webmaster-tools-bulk-url-removal.popup
  (:require-macros [chromex.support :refer [runonce]])
  (:require [google-webmaster-tools-bulk-url-removal.popup.core :as core]))

(runonce
  (core/init!))
