(ns google-webmaster-tools-bulk-url-removal.background
  (:require-macros [chromex.support :refer [runonce]])
  (:require [google-webmaster-tools-bulk-url-removal.background.core :as core]))

(runonce
  (core/init!))
