(defproject noitcudni/google-webmaster-tools-bulk-url-removal "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.520"]
                 [org.clojure/core.async "0.4.500"]
                 [binaryage/chromex "0.8.1"]
                 ;; [binaryage/chromex "0.8.5"]
                 [binaryage/devtools "0.9.10"]
                 [com.cognitect/transit-cljs "0.8.256"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 [com.cemerick/url "0.1.1"]
                 [hipo "0.5.2"]
                 [prismatic/dommy "1.1.0"]
                 [testdouble/clojurescript.csv "0.4.5"]
                 [domina "1.0.3"]
                 [reagent "0.8.1"]
                 [re-com "2.6.0"]
                 [figwheel "0.5.19"]
                 [environ "1.1.0"]]

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-figwheel "0.5.19"]
            [lein-shell "0.5.0"]
            [lein-environ "1.1.0"]
            [lein-cooper "1.2.2"]]

  :source-paths ["src/background"
                 "src/popup"
                 "src/content_script"]

  :clean-targets ^{:protect false} ["target"
                                    "resources/unpacked/compiled"
                                    "resources/release/compiled"]

  :cljsbuild {:builds {}}                                                                                                     ; prevent https://github.com/emezeske/lein-cljsbuild/issues/413

  :profiles {:unpacked
             {:cljsbuild {:builds
                          {:background
                           {:source-paths ["src/background"]
                            :figwheel     true
                            :compiler     {:output-to     "resources/unpacked/compiled/background/main.js"
                                           :output-dir    "resources/unpacked/compiled/background"
                                           :asset-path    "compiled/background"
                                           :preloads      [devtools.preload figwheel.preload]
                                           :main          google-webmaster-tools-bulk-url-removal.background
                                           :optimizations :none
                                           :source-map    true}}
                           :popup
                           {:source-paths ["src/popup"]
                            :figwheel     true
                            :compiler     {:output-to     "resources/unpacked/compiled/popup/main.js"
                                           :output-dir    "resources/unpacked/compiled/popup"
                                           :asset-path    "compiled/popup"
                                           :preloads      [devtools.preload figwheel.preload]
                                           :main          google-webmaster-tools-bulk-url-removal.popup
                                           :optimizations :none
                                           :source-map    true}}}}}
             :unpacked-content-script
             {:cljsbuild {:builds
                          {:content-script
                           {:source-paths ["src/content_script"]
                            :compiler     {:output-to     "resources/unpacked/compiled/content-script/main.js"
                                           :output-dir    "resources/unpacked/compiled/content-script"
                                           :asset-path    "compiled/content-script"
                                           :main          google-webmaster-tools-bulk-url-removal.content-script
                                           ;:optimizations :whitespace                                                        ; content scripts cannot do eval / load script dynamically
                                           :optimizations :advanced                                                           ; let's use advanced build with pseudo-names for now, there seems to be a bug in deps ordering under :whitespace mode
                                           :pseudo-names  true
                                           :pretty-print  true}}
                           ;; :content-script-2
                           ;; {:source-paths ["src/content_script"]
                           ;;  :compiler {:output-to     "resources/unpacked/compiled/removals_request/removals_request.js"
                           ;;             :output-dir    "resources/unpacked/compiled/removals_request"
                           ;;             :asset-path    "compiled/removals_request"
                           ;;             :main          google-webmaster-tools-bulk-url-removal.removals-request
                           ;;              ;:optimizations :whitespace                                                        ; content scripts cannot do eval / load script dynamically
                           ;;             :optimizations :advanced                                                           ; let's use advanced build with pseudo-names for now, there seems to be a bug in deps ordering under :whitespace mode
                           ;;             :pseudo-names  true
                           ;;             :pretty-print  true}}
                           }}}
             :checkouts
             ; DON'T FORGET TO UPDATE scripts/ensure-checkouts.sh
             {:cljsbuild {:builds
                          {:background {:source-paths ["checkouts/cljs-devtools/src/lib"
                                                       "checkouts/chromex/src/lib"
                                                       "checkouts/chromex/src/exts"]}
                           :popup      {:source-paths ["checkouts/cljs-devtools/src/lib"
                                                       "checkouts/chromex/src/lib"
                                                       "checkouts/chromex/src/exts"]}}}}
             :checkouts-content-script
             ; DON'T FORGET TO UPDATE scripts/ensure-checkouts.sh
             {:cljsbuild {:builds
                          {:content-script {:source-paths ["checkouts/cljs-devtools/src/lib"
                                                           "checkouts/chromex/src/lib"
                                                           "checkouts/chromex/src/exts"]}
                           ;; :content-script-2 {:source-paths ["checkouts/cljs-devtools/src/lib"
                           ;;                                   "checkouts/chromex/src/lib"
                           ;;                                   "checkouts/chromex/src/exts"]}
                           }}}

             :figwheel
             {:figwheel {:server-port    6888
                         :server-logfile ".figwheel.log"
                         :repl           true}}

             :disable-figwheel-repl
             {:figwheel {:repl false}}

             :cooper
             {:cooper {"content-dev"     ["lein" "content-dev"]
                       "fig-dev-no-repl" ["lein" "fig-dev-no-repl"]
                       "browser"         ["scripts/launch-test-browser.sh"]}}

             :release
             {:env       {:chromex-elide-verbose-logging "true"}
              :cljsbuild {:builds
                          {:background
                           {:source-paths ["src/background"]
                            :compiler     {:output-to     "resources/release/compiled/background.js"
                                           :output-dir    "resources/release/compiled/background"
                                           :asset-path    "compiled/background"
                                           :main          google-webmaster-tools-bulk-url-removal.background
                                           :optimizations :advanced
                                           :elide-asserts true}}
                           :popup
                           {:source-paths ["src/popup"]
                            :compiler     {:output-to     "resources/release/compiled/popup.js"
                                           :output-dir    "resources/release/compiled/popup"
                                           :asset-path    "compiled/popup"
                                           :main          google-webmaster-tools-bulk-url-removal.popup
                                           :optimizations :advanced
                                           :elide-asserts true}}
                           :content-script
                           {:source-paths ["src/content_script"]
                            :compiler     {:output-to     "resources/release/compiled/content-script.js"
                                           :output-dir    "resources/release/compiled/content-script"
                                           :asset-path    "compiled/content-script"
                                           :main          google-webmaster-tools-bulk-url-removal.content-script
                                           :optimizations :advanced
                                           :elide-asserts true}}
                           ;; :content-script-2
                           ;; {:source-paths ["src/content_script"]
                           ;;  :compiler     {:output-to     "resources/release/compiled/removals_request.js"
                           ;;                 :output-dir    "resources/release/compiled/removals_request"
                           ;;                 :asset-path    "compiled/removals_request"
                           ;;                 :main          google-webmaster-tools-bulk-url-removal.removals-request
                           ;;                 :optimizations :advanced
                           ;;                 :elide-asserts true}}
                           }}}}

  :aliases {"dev-build"       ["with-profile" "+unpacked,+unpacked-content-script,+checkouts,+checkouts-content-script" "cljsbuild" "once"]
            "fig"             ["with-profile" "+unpacked,+figwheel" "figwheel" "background" "popup"]
            ;; "content"         ["with-profile" "+unpacked-content-script" "cljsbuild" "auto" "content-script" "content-script-2"]
            "content"         ["with-profile" "+unpacked-content-script" "cljsbuild" "auto" "content-script"]
            "fig-dev-no-repl" ["with-profile" "+unpacked,+figwheel,+disable-figwheel-repl,+checkouts" "figwheel" "background" "popup"]
            "content-dev"     ["with-profile" "+unpacked-content-script,+checkouts-content-script" "cljsbuild" "auto"]
            "devel"           ["with-profile" "+cooper" "do"                                                                  ; for mac only
                               ["shell" "scripts/ensure-checkouts.sh"]
                               ["cooper"]]
            "release"         ["with-profile" "+release" "do"
                               ["clean"]
                               ;; ["cljsbuild" "once" "background" "popup" "content-script" "content-script-2"]
                               ["cljsbuild" "once" "background" "popup" "content-script"]
                               ]
            "package"         ["shell" "scripts/package.sh"]})
