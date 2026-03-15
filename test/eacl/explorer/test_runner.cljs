(ns eacl.explorer.test-runner
  (:require [cljs.test :as t]
            [eacl.explorer.core-test]
            [eacl.explorer.explorer-test]
            [eacl.explorer.seed-test]
            [eacl.explorer.state-test]))

(defn init!
  []
  (js/console.info "EACL Explorer test build ready. Use the cljs test REPL to run suites."))

(defn run-all-tests!
  []
  (t/run-tests 'eacl.explorer.core-test
               'eacl.explorer.seed-test
               'eacl.explorer.explorer-test
               'eacl.explorer.state-test))
