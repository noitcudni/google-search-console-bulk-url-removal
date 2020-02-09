# Google Search Console Bulk Url Removal Chrome Extension
## Install from Google Webstore
https://chrome.google.com/webstore/detail/webmaster-tools-bulk-url/pmnpibilljafelnghknefahibdnfeece

## Installation
1. Install Java.
2. Install [leiningen](http://leiningen.org).
3. Either `git clone git@github.com:noitcudni/google-webmaster-tools-bulk-url-removal.git` or download the zip file from [github](https://github.com/noitcudni/google-webmaster-tools-bulk-url-removal/archive/master.zip) and unzip it.
4. `cd` into the project root directory.
  * Run in the terminal
  ```bash
  lein release && lein package
  ```
5. Go to **chrome://extensions/** and turn on Developer mode.
6. Click on **Load unpacked extension . . .** and load the extension.

## Usage
1. Create a list of urls to be removed and store them in a file. All urls are separated by \n. See [How to Extract a List of All URLs Indexed by Google for Your Website](https://cognitiveseo.com/blog/5714/69-amazing-seo-bookmarklets-to-supercharge-your-internet-marketing/#1-2) for an easy way to get all links from a website from Google Search results.
2. Go to Google's Search Console formerly known as Google's Webmaster Tools. (https://search.google.com/u/1/search-console)
4. Click on Removals on the left panel.
5. Click on  "Choose File" button to upload your csv file. It wil start running automatically.

## Local Storage
The extension uses chrome's local storage to keep track of state for each URL. You can use the **Clear local storage** button to clear your local storage content to start anew.

~~You may run into the `quota exceeded` error. Should you encounter this error, the extension will pause of the removal process and present to you a `continue` button. Wait a considerable amount of time (I have no idea how long) and click on `continue` to pick up where it has left off.~~

~~While the extension is at a paused state, you can also inspect what URLs are still pending to be removed. Right click anywhere on the page and select **Inspect**. Then, click on the **View local storage** button. The current local storage state will be printed out under the **Console** tab.~~

## CSV Format
url (required), removal-method (optional: remove-url, clear-cached), url-type (optional: url-only, prefix)
By default `removal-method` is set to `removel-url`, and `url-type` is set to `url-only`

## Bulk Outdated Content Removal
For removing outdated content in bulk. Please visit [https://github.com/noitcudni/google-webmaster-tools-bulk-outdated-content-removal](https://github.com/noitcudni/google-webmaster-tools-bulk-outdated-content-removal)
