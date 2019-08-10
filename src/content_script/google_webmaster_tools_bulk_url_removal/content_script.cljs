(ns google-webmaster-tools-bulk-url-removal.content-script
  (:require-macros [chromex.support :refer [runonce]])
  (:require [google-webmaster-tools-bulk-url-removal.content-script.core :as core]))

(runonce
 (core/init!))
