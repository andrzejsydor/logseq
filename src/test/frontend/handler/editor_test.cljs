(ns frontend.handler.editor-test
  (:require [frontend.handler.editor :as editor]
            [frontend.db :as db]
            [clojure.test :refer [deftest is testing are use-fixtures]]
            [datascript.core :as d]
            [frontend.test.helper :as test-helper :refer [load-test-files]]
            [frontend.db.model :as model]
            [frontend.state :as state]
            [frontend.util.cursor :as cursor]))

(use-fixtures :each test-helper/start-and-destroy-db)

(deftest extract-nearest-link-from-text-test
  (testing "Page, block and tag links"
    (is (= "page1"
           (editor/extract-nearest-link-from-text "[[page1]] [[page2]]" 0))
        "Finds first page link correctly based on cursor position")

    (is (= "page2"
           (editor/extract-nearest-link-from-text "[[page1]] [[page2]]" 10))
        "Finds second page link correctly based on cursor position")

    (is (= "tag"
           (editor/extract-nearest-link-from-text "#tag [[page1]]" 3))
        "Finds tag correctly")

    (is (= "61e057b9-f799-4532-9258-cfef6ce58370"
           (editor/extract-nearest-link-from-text
            "((61e057b9-f799-4532-9258-cfef6ce58370)) #todo" 5))
        "Finds block correctly"))

  (testing "Url links"
    (is (= "https://github.com/logseq/logseq"
           (editor/extract-nearest-link-from-text
            "https://github.com/logseq/logseq is #awesome :)" 0 editor/url-regex))
        "Finds url correctly")

    (is (not= "https://github.com/logseq/logseq"
              (editor/extract-nearest-link-from-text
               "https://github.com/logseq/logseq is #awesome :)" 0))
        "Doesn't find url if regex not passed")

    (is (= "https://github.com/logseq/logseq"
           (editor/extract-nearest-link-from-text
            "[logseq](https://github.com/logseq/logseq) is #awesome :)" 0 editor/url-regex))
        "Finds url in markdown link correctly"))

  (is (= "https://github.com/logseq/logseq"
         (editor/extract-nearest-link-from-text
          "[[https://github.com/logseq/logseq][logseq]] is #awesome :)" 0 editor/url-regex))
      "Finds url in org link correctly"))

(defn- set-marker
  "Spied version of editor/set-marker"
  [marker content format]
  (let [actual-content (atom nil)]
    (with-redefs [editor/save-block-if-changed! (fn [_ content]
                                                  (reset! actual-content content))]
      (editor/set-marker {:block/marker marker :block/title content :block/format format})
      @actual-content)))

(deftest set-marker-org
  (are [marker content expect] (= expect (set-marker marker content :org))
    "TODO" "TODO content" "DOING content"
    "TODO" "** TODO content" "** DOING content"
    "TODO" "## TODO content" "DOING ## TODO content"
    "DONE" "DONE content" "content"))

(deftest set-marker-markdown
  (are [marker content expect] (= expect (set-marker marker content :markdown))
    "TODO" "TODO content" "DOING content"
    "TODO" "## TODO content" "## DOING content"
    "DONE" "DONE content" "content"))

(defn- keyup-handler
  "Spied version of editor/keyup-handler"
  [{:keys [value cursor-pos action commands]
    ;; Default to some commands matching which matches default behavior for most
    ;; completion scenarios
    :or {commands [:fake-command]}}]
  ;; Reset editor action in order to test result
  (state/set-editor-action! action)
  ;; Default cursor pos to end of line
  (let [pos (or cursor-pos (count value))
        input #js {:value value}
        command (subs value 1)]
    (with-redefs [editor/get-last-command (constantly command)
                  editor/get-matched-commands (constantly commands)
                  ;; Ignore as none of its behaviors are tested
                  editor/default-case-for-keyup-handler (constantly nil)
                  cursor/pos (constantly pos)]
      ((editor/keyup-handler nil input)
       #js {:key (subs value (dec (count value)))}
       nil))))

(deftest keyup-handler-test
  (testing "Command autocompletion"
    ;; default last matching command is ""
    (keyup-handler {:value "/z"
                    :action :commands
                    :commands []})
    (is (= :commands (state/get-editor-action))
        "Completion stays open if no matches but differs from last success by <= 2 chars")

    (keyup-handler {:value "/zz"
                    :action :commands
                    :commands []})
    (is (= :commands (state/get-editor-action))
        "Completion stays open if no matches but differs from last success by <= 2 chars")

    (keyup-handler {:value "/zzz"
                    :action :commands
                    :commands []})
    (is (= nil (state/get-editor-action))
        "Completion closed if no matches and > 2 chars form last success")

    (keyup-handler {:value "/b"
                    :action :commands
                    :commands [:fake-command]})
    (is (= :commands (state/get-editor-action))
        "Completion stays open if there is a matching command")

    (keyup-handler {:value "/ " :action :commands})
    (is (= nil (state/get-editor-action))
        "Completion closed after a space follows /")

    (keyup-handler {:value "/block " :action :commands})
    (is (= :commands (state/get-editor-action))
        "Completion stays open if space is part of the search term for /"))

  (testing "Tag autocompletion"
    (keyup-handler {:value "foo #b" :action :page-search-hashtag})
    (is (= :page-search-hashtag (state/get-editor-action))
        "Completion stays open for one tag")

    (keyup-handler {:value "text # #bar"
                    :action :page-search-hashtag
                    :cursor-pos 6})
    (is (= :page-search-hashtag (state/get-editor-action))
        "Completion stays open when typing tag before another tag"))
  ;; Reset state
  (state/set-editor-action! nil))

(defn- handle-last-input-handler
  "Spied version of editor/handle-last-input"
  [{:keys [value cursor-pos]}]
  ;; Reset editor action in order to test result
  (state/set-editor-action! nil)
  ;; Default cursor pos to end of line
  (let [pos (or cursor-pos (count value))]
    (with-redefs [state/get-input (constantly #js {:value value})
                  cursor/pos (constantly pos)
                  cursor/move-cursor-backward (constantly nil) ;; ignore if called
                  cursor/get-caret-pos (constantly {})]
      (editor/handle-last-input))))

(deftest handle-last-input-handler-test
  (testing "Property autocompletion"
    (handle-last-input-handler {:value "::"})
    (is (= :property-search (state/get-editor-action))
        "Autocomplete properties if only colons have been typed")

    (handle-last-input-handler {:value "foo::bar\n::"})
    (is (= :property-search (state/get-editor-action))
        "Autocomplete properties if typing colons on a second line")

    (handle-last-input-handler {:value "middle of line::"})
    (is (= nil (state/get-editor-action))
        "Don't autocomplete properties if typing colons in the middle of a line")

    (handle-last-input-handler {:value "first \nfoo::bar"
                                :cursor-pos (dec (count "first "))})
    (is (= nil (state/get-editor-action))
        "Don't autocomplete properties if typing in a block where properties already exist"))

  (testing "Command autocompletion"
    (handle-last-input-handler {:value "/"})
    (is (= :commands (state/get-editor-action))
        "Command search if only / has been typed")

    (handle-last-input-handler {:value "some words /"})
    (is (= :commands (state/get-editor-action))
        "Command search on start of new word")

    (handle-last-input-handler {:value "a line\n/"})
    (is (= :commands (state/get-editor-action))
        "Command search on start of a new line")

    (handle-last-input-handler {:value "https://"})
    (is (= nil (state/get-editor-action))
        "No command search in middle of a word")

    (handle-last-input-handler {:value "#blah/"})
    (is (= nil (state/get-editor-action))
        "No command search after a tag search to allow for namespace completion"))

  (testing "Tag autocompletion"
    (handle-last-input-handler {:value "#"
                                :cursor-pos 1})
    (is (= :page-search-hashtag (state/get-editor-action))
        "Page search if only hashtag has been typed")

    (handle-last-input-handler {:value "foo #"
                                :cursor-pos 5})
    (is (= :page-search-hashtag (state/get-editor-action))
        "Page search if hashtag has been typed at EOL")

    (handle-last-input-handler {:value "#Some words"
                                :cursor-pos 1})
    (is (= :page-search-hashtag (state/get-editor-action))
        "Page search if hashtag is at start of line and there are existing words")

    (handle-last-input-handler {:value "foo #"
                                :cursor-pos 5})
    (is (= :page-search-hashtag (state/get-editor-action))
        "Page search if hashtag is at EOL and after a space")

    (handle-last-input-handler {:value "foo #bar"
                                :cursor-pos 5})
    (is (= :page-search-hashtag (state/get-editor-action))
        "Page search if hashtag is in middle of line and after a space")

    (handle-last-input-handler {:value "String#" :cursor-pos 7})
    (is (= nil (state/get-editor-action))
        "No page search if hashtag has been typed at end of a word")

    (handle-last-input-handler {:value "foo#bar" :cursor-pos 4})
    (is (= nil (state/get-editor-action))
        "No page search if hashtag is in middle of word")

    (handle-last-input-handler {:value "`String#gsub and String#`"
                                :cursor-pos (dec (count "`String#gsub and String#`"))})
    (is (= nil (state/get-editor-action))
        "No page search within backticks"))
  ;; Reset state
  (state/set-editor-action! nil))

(deftest save-block-aux!
  (load-test-files [{:file/path "pages/page1.md"
                     :file/content "\n
- b1 #foo"}])
  (testing "updating block's content changes content and preserves path-refs"
   (let [conn (db/get-db test-helper/test-db false)
         block (->> (d/q '[:find (pull ?b [* {:block/path-refs [:block/name]}])
                           :where [?b :block/title "b1 #foo"]]
                         @conn)
                    ffirst)
         prev-path-refs (set (map :block/name (:block/path-refs block)))
         _ (assert (= #{"page1" "foo"} prev-path-refs)
                   "block has expected :block/path-refs")
         ;; Use same options as edit-box-on-change!
         _ (editor/save-block-aux! block "b12 #foo" {:skip-properties? true})
         updated-block (d/pull @conn '[* {:block/path-refs [:block/name]}] [:block/uuid (:block/uuid block)])]
     (is (= "b12 #foo" (:block/title updated-block)) "Content updated correctly")
     (is (= prev-path-refs
            (set (map :block/name (:block/path-refs updated-block))))
         "Path-refs remain the same"))))

(deftest save-block!
  (testing "Saving blocks with and without properties"
    (test-helper/load-test-files [{:file/path "foo.md"
                                   :file/content "# foo"}])
    (let [repo test-helper/test-db
          page-uuid (:block/uuid (db/get-page "foo"))
          block-uuid (:block/uuid (model/get-block-by-page-name-and-block-route-name repo (str page-uuid) "foo"))]
      (editor/save-block! repo block-uuid "# bar")
      (is (= "# bar" (:block/title (model/query-block-by-uuid block-uuid))))

      (editor/save-block! repo block-uuid "# foo" {:properties {:foo "bar"}})
      (is (= "# foo\nfoo:: bar" (:block/title (model/query-block-by-uuid block-uuid))))

      (editor/save-block! repo block-uuid "# bar")
      (is (= "# bar" (:block/title (model/query-block-by-uuid block-uuid)))))))
