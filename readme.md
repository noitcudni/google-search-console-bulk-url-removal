# Google Webmaster Tools Bulk Url Removal Chrome Extension
## Install from Google Webstore
https://chrome.google.com/webstore/detail/webmaster-tools-bulk-url/pmnpibilljafelnghknefahibdnfeece

## Installation
1. Install Java.
2. Install [leiningen](http://leiningen.org).
3. Either `git clone git@github.com:noitcudni/google-webmaster-tools-bulk-url-removal.git` or download the zip file from [github](https://github.com/noitcudni/google-webmaster-tools-bulk-url-removal/archive/master.zip) and unzip it.
4. `cd` into the project root directory.
  * Run in the terminal
  ```bash
  len release && lein package
  ```
5. Go to **chrome://extensions/** and turn on Developer mode.
6. Click on **Load unpacked extension . . .** and load the extension.

## Usage
1. Create a list of urls to be removed and store them in a file. All urls are separated by \n. See [How to Extract a List of All URLs Indexed by Google for Your Website](https://cognitiveseo.com/blog/5714/69-amazing-seo-bookmarklets-to-supercharge-your-internet-marketing/#1-2) for an easy way to get all links from a website from Google Search results.
2. Go to Google's webmaster tools.
3. Check to ensure that all URLs match the property you are updating, including the scheme (http vs https)
4. Click on Google Index -> Remove URLs.
5. You should now see a new drop-down with several removal options, along with a "Choose File" button.
  * **NOTE**: the drop-down is a global option. You can specify a different removal option on a per URL basis to override the global option. Please refer to the **CSV Format section** below.
6. Click on the Choose File button.
7. Select the file you created in step 3.

## Local Storage
The extension uses chrome's local storage to keep track of state for each URL. You can use the **Clear local storage** button to clear your local storage content to start anew.

You may run into the `quota exceeded` error. Should you encounter this error, the extension will pause of the removal process and present to you a `continue` button. Wait a considerable amount of time (I have no idea how long) and click on `continue` to pick up where it has left off.

While the extension is at a paused state, you can also inspect what URLs are still pending to be removed. Right click anywhere on the page and select **Inspect**. Then, click on the **View local storage** button. The current local storage state will be printed out under the **Console** tab.

## CSV Format
url (required), method-of-removal (optional options: PAGE, PAGE_CACHE, DIRECTORY)

* **PAGE**       : corresponds to **Remove page from search results and cache**
* **PAGE_CACHE** : corresponds to **Remove page from cache only**
* **DRIECTORY**  : corresponds to **Remove Directory**

**NOTE**: method-of-removal inside CSV overrides the global removal option.

## Bulk Outdated Content Removal
For removing outdated content in bulk. Please visit [https://github.com/noitcudni/google-webmaster-tools-bulk-outdated-content-removal](https://github.com/noitcudni/google-webmaster-tools-bulk-outdated-content-removal)
