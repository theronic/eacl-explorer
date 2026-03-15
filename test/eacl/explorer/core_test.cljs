(ns eacl.explorer.core-test
  (:require [cljs.test :refer-macros [deftest is]]
            [eacl.explorer.core :as core]))

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
