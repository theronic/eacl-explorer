(ns eacl.explorer.core-test
  (:require [cljs.test :refer-macros [deftest is]]
            [eacl.explorer.core :as core]
            [eacl.explorer.state :as state]))

(deftest identifier-label-is-safe-for-ui-display
  (is (= "server" (core/identifier-label :server)))
  (is (= "server" (core/identifier-label 'server)))
  (is (= "server" (core/identifier-label "server")))
  (is (nil? (core/identifier-label nil))))

(deftest permission-label-handles-non-keyword-values
  (is (= ":view" (core/permission-label :view)))
  (is (= ":view" (core/permission-label 'view)))
  (is (= ":view" (core/permission-label "view")))
  (is (= "No permission" (core/permission-label nil))))

(deftest cache-timing-renders-page-provenance-before-duration
  (let [timing (core/cache-timing :hit "1.20ms")]
    (is (= :span.cache-badge (first (nth timing 2))))
    (is (= "HIT" (last (nth timing 2))))
    (is (= [:span.cache-timing__duration "1.20ms"]
           (nth timing 3)))))

(deftest schema-preset-tabs-replace-only-the-unsaved-draft
  (let [drafts (atom [])
        presets [{:id :non-recursive
                  :label "Non-recursive"
                  :schema "non-recursive schema"}
                 {:id :recursive
                  :label "Recursive"
                  :schema "recursive schema"}]
        tabs (core/schema-preset-tabs "non-recursive schema" presets)
        buttons (filter
                 #(= :button.schema-preset-tab (first %))
                 (filter vector? (tree-seq vector? seq tabs)))
        recursive-button (second buttons)]
    (is (= 2 (count buttons)))
    (is (true? (:aria-selected (second (first buttons)))))
    (is (false? (:aria-selected (second recursive-button))))
    (with-redefs [state/set-schema-draft! #(swap! drafts conj %)]
      ((:on-click (second recursive-button)) nil))
    (is (= ["recursive schema"] @drafts))))
