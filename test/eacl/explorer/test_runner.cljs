(ns eacl.explorer.test-runner
  (:require [eacl.explorer.explorer-test]
            [eacl.explorer.seed-test]
            [eacl.explorer.state-test]))

(defn init!
  []
  (js/console.info "EACL Explorer test build ready. Use the cljs test REPL to run suites."))
