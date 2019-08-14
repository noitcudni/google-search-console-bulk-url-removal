(ns google-webmaster-tools-bulk-url-removal.removals-request
  (:require-macros [chromex.support :refer [runonce]])
  (:require [google-webmaster-tools-bulk-url-removal.content-script.removals :as removals]))

(runonce
 (removals/init!))
