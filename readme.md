#### **google-webmaster-tools-bulk-url-removal** project has following configuration:
  * uses [leiningen](http://leiningen.org) + [lein-cljsbuild](https://github.com/emezeske/lein-cljsbuild)
  * integrates [cljs-devtools](https://github.com/binaryage/cljs-devtools)
  * integrates [figwheel](https://github.com/bhauman/lein-figwheel) (for background page and popup buttons)
  * under `:unpacked` profile (development)
    * background page and popup button
      * compiles with `optimizations :none`
      * namespaces are included as individual files and source maps work as expected
      * figwheel works
    * content script
      * due to [security restrictions], content script has to be provided as a single file
      * compiles with `:optimizations :whitespace` and `:pretty-print true`
      * figwheel cannot be used in this context (eval is not allowed)
  * under `:release` profile
    * background page, popup button and content script compile with `optimizations :advanced`
    * elides asserts
    * no figwheel support
    * no cljs-devtools support
    * `lein package` task is provided for building an extension package for release

### Local setup

#### Extension development

We assume you are familiar with ClojureScript tooling and you have your machine in a good shape running recent versions of
java, maven, leiningen, etc.

  * clone this repo somewhere:
    ```bash
    git clone
    ```
  * google-webmaster-tools is gets built into `resources/unpacked/compiled` folder.

    In one terminal session run (will build background and popup pages using figwheel):
    ```bash
    lein fig
    ```
    In a second terminal session run (will auto-build content-script):
    ```bash
    lein content
    ```
  * In Chrome Canary, open `chrome://extensions` and add `resources/unpacked` via "Load unpacked extension..."
