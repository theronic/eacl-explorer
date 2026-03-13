(ns eacl.explorer.state-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [eacl.core :as eacl]
            [eacl.explorer.explorer :as explorer]
            [eacl.explorer.seed :as seed]
            [eacl.explorer.state :as state]))

(defn- reset-state!
  []
  (reset! state/!runtime nil)
  (reset! state/!app {:bootstrap state/default-bootstrap
                      :ui        explorer/default-ui-state
                      :counts    explorer/default-count-state
                      :child-sections {}
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
                      :child-sections {"account|account-0001>server" {:status "ready"}}
                      :db-rev    0})
  (with-redefs [state/start-count-jobs! (fn [] nil)
                state/restart-expanded-child-section-jobs! (fn [] nil)]
    (state/select-subject! "user-2"))
  (is (= "user-2" (get-in @state/!app [:ui :subject-id])))
  (is (= {:type :server :id "server-0001-0001"}
         (get-in @state/!app [:ui :selected-resource])))
  (is (= {} (get-in @state/!app [:ui :group-prev])))
  (is (= {} (get-in @state/!app [:ui :nested-prev])))
  (is (= {} (:child-sections @state/!app)))
  (is (= explorer/default-count-state (:counts @state/!app))))

(deftest select-permission-resets-pagination-and-persists-permission
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        (assoc explorer/default-ui-state
                                   :group-prev {:server ["cursor-1"]}
                                   :nested-prev {"account|account-0001>server" ["server-0001-0005"]})
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :child-sections {"account|account-0001>server" {:status "ready"}}
                      :db-rev    0})
  (with-redefs [state/start-count-jobs! (fn [] nil)
                state/restart-expanded-child-section-jobs! (fn [] nil)]
    (state/select-permission! :admin))
  (is (= :admin (get-in @state/!app [:ui :permission])))
  (is (= {} (get-in @state/!app [:ui :group-prev])))
  (is (= {} (get-in @state/!app [:ui :nested-prev])))
  (is (= {} (:child-sections @state/!app)))
  (is (= explorer/default-count-state (:counts @state/!app))))

(deftest toggle-schema-flips-panel-visibility
  (state/toggle-schema!)
  (is (true? (get-in @state/!app [:ui :schema-expanded?])))
  (state/toggle-schema!)
  (is (false? (get-in @state/!app [:ui :schema-expanded?]))))

(deftest db-change-invalidates-counts-and-bumps-db-rev
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        explorer/default-ui-state
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :child-sections {"account|account-0001>server" {:status "ready"}}
                      :db-rev    4})
  (let [job-restarts (atom 0)]
    (with-redefs [state/start-count-jobs! (fn [] (swap! job-restarts inc))
                  state/restart-expanded-child-section-jobs! (fn [] nil)]
      (state/on-db-change!))
    (is (= 5 (:db-rev @state/!app)))
    (is (= {} (:child-sections @state/!app)))
    (is (= explorer/default-count-state (:counts @state/!app)))
    (is (= 1 @job-restarts))))

(deftest db-change-during-seeding-skips-count-restart
  (reset! state/!app {:bootstrap (merge (ready-bootstrap)
                                   {:status :seeding})
                      :ui        explorer/default-ui-state
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :child-sections {"account|account-0001>server" {:status "ready"}}
                      :db-rev    4})
  (let [job-restarts (atom 0)]
    (with-redefs [state/start-count-jobs! (fn [] (swap! job-restarts inc))
                  state/restart-expanded-child-section-jobs! (fn [] (swap! job-restarts inc))]
      (state/on-db-change!))
    (is (= 5 (:db-rev @state/!app)))
    (is (= {} (:child-sections @state/!app)))
    (is (= explorer/default-count-state (:counts @state/!app)))
    (is (zero? @job-restarts))))

(deftest editing-controls-update-ui-state
  (state/set-seed-size! "2000")
  (state/set-schema-draft! "definition user {}")
  (is (= "2000" (get-in @state/!app [:ui :seed-size-input])))
  (is (= "definition user {}" (get-in @state/!app [:ui :schema-draft]))))

(deftest expanding-resource-restarts-visible-expanded-sections
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        (assoc explorer/default-ui-state
                                   :expanded-section-keys #{"account|account-0001>server"})
                      :counts    explorer/default-count-state
                      :child-sections {}
                      :db-rev    0})
  (let [called (atom nil)]
    (with-redefs [state/start-expanded-child-sections-for-resources!
                  (fn [resources] (reset! called resources))]
      (state/toggle-expanded-resource! {:type :account :id "account-0001"}))
    (is (= [{:type :account :id "account-0001"}] @called))
    (is (contains? (get-in @state/!app [:ui :expanded-resource-keys])
          "account|account-0001"))))

(deftest expanding-section-starts-child-section-loading
  (let [called (atom nil)]
    (with-redefs [state/ensure-section-key-loaded! (fn [section-key] (reset! called section-key))]
      (state/toggle-expanded-section! "account|account-0001>server"))
    (is (= "account|account-0001>server" @called))
    (is (contains? (get-in @state/!app [:ui :expanded-section-keys])
                   "account|account-0001>server"))))

(deftest child-section-total-finalization-preserves-visible-page-state
  (let [section-key "account|account-0001>server"
        job-id      "job-1"
        context     {:db-rev        0
                     :subject-id    "user-2"
                     :permission    :view
                     :parent-type   :account
                     :parent-id     "account-0001"
                     :resource-type :server
                     :cursor-token  nil}]
    (swap! state/!app assoc-in [:child-sections section-key]
           (merge context {:status      "loading"
                           :job-id      job-id
                           :items       []
                           :total       nil
                           :total-status "loading"
                           :page-start  0
                           :page-end    0
                           :next-cursor nil
                           :time        nil
                           :error       nil}))
    (#'state/publish-child-section! section-key job-id context
       {:status      "ready"
        :items       [{:type :server :id "server-0001-0001"}]
        :total       nil
        :total-status "loading"
        :page-start  1
        :page-end    1
        :next-cursor "server-0001-0001"
        :time        1000000
        :error       nil}
       false)
    (#'state/publish-child-section! section-key job-id context
       {:total        2000
        :total-status "ready"}
       true)
    (is (= "ready" (get-in @state/!app [:child-sections section-key :status])))
    (is (= [{:type :server :id "server-0001-0001"}]
           (get-in @state/!app [:child-sections section-key :items])))
    (is (= 1 (get-in @state/!app [:child-sections section-key :page-start])))
    (is (= 1 (get-in @state/!app [:child-sections section-key :page-end])))
    (is (= "server-0001-0001"
           (get-in @state/!app [:child-sections section-key :next-cursor])))
    (is (= 2000 (get-in @state/!app [:child-sections section-key :total])))
    (is (= "ready" (get-in @state/!app [:child-sections section-key :total-status])))
    (is (nil? (get-in @state/!app [:child-sections section-key :job-id])))))

(deftest selecting-resource-normalizes-permission-to-selected-resource-schema
  (let [{:keys [conn client]} (seed/create-runtime)
        server-viewless
        "definition user {}

         definition platform {
           relation super_admin: user
         }

         definition account {
           relation owner: user
           relation platform: platform

           permission admin = owner + platform->super_admin
           permission view = admin
         }

         definition team {
           relation account: account
           relation leader: user

           permission admin = account->admin + leader
           permission view = admin
         }

         definition vpc {
           relation account: account
           relation shared_admin: user

           permission admin = account->admin + shared_admin
           permission view = admin
         }

         definition server {
           relation account: account
           relation team: team
           relation vpc: vpc
           relation shared_admin: user

           permission admin = account->admin + shared_admin
         }"]
    (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
    (eacl/write-schema! client server-viewless)
    (reset! state/!runtime {:conn conn :client client})
    (reset! state/!app {:bootstrap (ready-bootstrap)
                        :ui        (assoc explorer/default-ui-state
                                     :permission :view)
                        :counts    explorer/default-count-state
                        :child-sections {}
                        :db-rev    0})
    (let [count-restarts (atom 0)
          child-restarts (atom 0)]
      (with-redefs [state/start-count-jobs! (fn [] (swap! count-restarts inc))
                    state/restart-expanded-child-section-jobs! (fn [] (swap! child-restarts inc))]
        (state/select-resource! {:type :server :id "server-0001-0001"}))
      (is (= {:type :server :id "server-0001-0001"}
             (get-in @state/!app [:ui :selected-resource])))
      (is (= :admin (get-in @state/!app [:ui :permission])))
      (is (= 1 @count-restarts))
      (is (= 1 @child-restarts)))))
