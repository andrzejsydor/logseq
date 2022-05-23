(ns frontend.mobile.util
  (:require ["@capacitor/core" :refer [Capacitor registerPlugin]]
            ["@capacitor/splash-screen" :refer [SplashScreen]]
            [clojure.string :as string]))

(defn platform []
  (.getPlatform Capacitor))

(defn native-platform? []
  (.isNativePlatform Capacitor))

(defn native-ios? []
  (and (native-platform?)
       (= (platform) "ios")))

(defn native-android? []
  (and (native-platform?)
       (= (platform) "android")))

(defn convert-file-src [path-str]
  (.convertFileSrc Capacitor path-str))

(defonce folder-picker (registerPlugin "FolderPicker"))
(when (native-ios?)
  (defonce download-icloud-files (registerPlugin "DownloadiCloudFiles"))
  (defonce ios-file-container (registerPlugin "FileContainer"))
  (defonce file-sync (registerPlugin "FileSync")))

;; NOTE: both iOS and android share the same FsWatcher API
(when (native-platform?)
  (defonce fs-watcher (registerPlugin "FsWatcher")))

(defn sync-icloud-repo [repo-dir]
  (let [repo-name (-> (string/split repo-dir "Documents/")
                      last
                      string/trim
                      js/decodeURI)]
    (.syncGraph download-icloud-files
                (clj->js {:graph repo-name}))))

(defn hide-splash []
  (.hide SplashScreen))

(defn get-idevice-model
  []
  (when (native-ios?)
    (let [width (.-width js/screen)
          height (.-height js/screen)
          landscape? (> width height)
          [width height] (if landscape? [height width] [width height])]
      [(case [width height]
         [320 568] "iPhoneSE4"
         [375 667] "iPhone8"
         [375 812] "iPhoneX"
         [390 844] "iPhone12"
         [414 736] "iPhone8Plus"
         [414 896] "iPhone11"
         [428 926] "iPhone13ProMax"
         [476 847] "iPhone7Plus"
         [744 1133] "iPadmini8.3"
         [768 1024] "iPad9.7"
         [810 1080] "iPad10.2"
         [820 1180] "iPad10.9"
         [834 1112] "iPadAir10.5"
         [834 1194] "iPadPro11"
         [1024 1366] "iPadPro12.9"
         "Not a known Apple device!")
       landscape?])))

(defn native-iphone-without-notch?
  []
  (when-let [model (get-idevice-model)]
    (string/starts-with? (first model) "iPhone8")))

(defn native-iphone?
  []
  (when-let [model (get-idevice-model)]
    (and (string/starts-with? (first model) "iPhone")
         (not (string/starts-with? (first model) "iPhone8")))))

(defn native-ipad?
  []
  (when-let [model (get-idevice-model)]
    (string/starts-with? (first model) "iPad")))
