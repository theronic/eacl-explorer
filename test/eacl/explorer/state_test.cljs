(ns eacl.explorer.state-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [eacl.explorer.explorer :as explorer]
            [eacl.explorer.seed :as seed]
            [eacl.explorer.state :as state]))

(defn- reset-state!
  []
  (reset! state/!runtime nil)
  (reset! state/!app {:bootstrap state/default-bootstrap
                      :ui        explorer/default-ui-state
                      :counts    explorer/default-count-state
                      :db-rev    0}))

(defn- ready-bootstrap
  []
  (merge state/default-bootstrap
    {:status :ready
     :totals seed/empty-totals}))

(use-fixtures :each
  (fn [run-tests]
    (reset-state!)
    (run-tests)
    (reset-state!)))

(deftest select-subject-resets-pagination-but-keeps-selection
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        (assoc explorer/default-ui-state
                                   :selected-resource {:type :server :id "server-0001-0001"}
                                   :group-prev {:server ["cursor-1"]}
                                   :nested-prev {"account|account-0001>server" ["server-0001-0005"]})
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :db-rev    0})
  (with-redefs [state/start-count-jobs! (fn [] nil)]
    (state/select-subject! "user-2"))
  (is (= "user-2" (get-in @state/!app [:ui :subject-id])))
  (is (= {:type :server :id "server-0001-0001"}
         (get-in @state/!app [:ui :selected-resource])))
  (is (= {} (get-in @state/!app [:ui :group-prev])))
  (is (= {} (get-in @state/!app [:ui :nested-prev])))
  (is (= explorer/default-count-state (:counts @state/!app))))

(deftest select-permission-resets-pagination-and-persists-permission
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        (assoc explorer/default-ui-state
                                   :group-prev {:server ["cursor-1"]}
                                   :nested-prev {"account|account-0001>server" ["server-0001-0005"]})
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :db-rev    0})
  (with-redefs [state/start-count-jobs! (fn [] nil)]
    (state/select-permission! :admin))
  (is (= :admin (get-in @state/!app [:ui :permission])))
  (is (= {} (get-in @state/!app [:ui :group-prev])))
  (is (= {} (get-in @state/!app [:ui :nested-prev])))
  (is (= explorer/default-count-state (:counts @state/!app))))

(deftest toggle-schema-flips-panel-visibility
  (state/toggle-schema!)
  (is (true? (get-in @state/!app [:ui :show-schema?])))
  (state/toggle-schema!)
  (is (false? (get-in @state/!app [:ui :show-schema?]))))

(deftest db-change-invalidates-counts-and-bumps-db-rev
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        explorer/default-ui-state
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :db-rev    4})
  (let [job-restarts (atom 0)]
    (with-redefs [state/start-count-jobs! (fn [] (swap! job-restarts inc))]
      (state/on-db-change!))
    (is (= 5 (:db-rev @state/!app)))
    (is (= explorer/default-count-state (:counts @state/!app)))
    (is (= 1 @job-restarts))))

(deftest db-change-during-seeding-skips-count-restart
  (reset! state/!app {:bootstrap (merge (ready-bootstrap)
                                   {:status :seeding})
                      :ui        explorer/default-ui-state
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :db-rev    4})
  (let [job-restarts (atom 0)]
    (with-redefs [state/start-count-jobs! (fn [] (swap! job-restarts inc))]
      (state/on-db-change!))
    (is (= 5 (:db-rev @state/!app)))
    (is (= explorer/default-count-state (:counts @state/!app)))
    (is (zero? @job-restarts))))

(deftest editing-controls-update-ui-state
  (state/set-seed-size! "2000")
  (state/set-schema-draft! "definition user {}")
  (is (= "2000" (get-in @state/!app [:ui :seed-size-input])))
  (is (= "definition user {}" (get-in @state/!app [:ui :schema-draft]))))
