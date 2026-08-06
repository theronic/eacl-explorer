(ns eacl.explorer.state-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [datascript.core :as d]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.explorer.explorer :as explorer]
            [eacl.explorer.seed :as seed]
            [eacl.explorer.state :as state]))

(defn- reset-state!
  []
  (reset! state/!runtime nil)
  (reset! state/!metrics-refresh-scheduled? false)
  (reset! state/!app {:bootstrap state/default-bootstrap
                      :ui        explorer/default-ui-state
                      :counts    explorer/default-count-state
                      :child-sections {}
                      :cache-metrics {:status "idle"
                                      :data nil
                                      :edn "{}"
                                      :error nil}
                      :db-rev    0}))

(defn- ready-bootstrap
  []
  (merge state/default-bootstrap
    {:status :ready
     :totals seed/empty-totals}))

(defn- install-runtime!
  []
  (let [runtime (seed/create-runtime)]
    (reset! state/!runtime runtime)
    runtime))

(defn- transact-ui!
  [conn attrs]
  (d/transact! conn
               [(assoc attrs :eacl/id seed/ui-state-marker-id)]))

(use-fixtures :each
  (fn [run-tests]
    (reset-state!)
    (run-tests)
    (reset-state!)))

(deftest select-subject-resets-pagination-but-keeps-selection
  (let [{:keys [conn]} (install-runtime!)]
    (transact-ui! conn
                  {:explorer.ui/selected-resource-type :server
                   :explorer.ui/selected-resource-id "server-0001-0001"})
    (reset! state/!app {:bootstrap (ready-bootstrap)
                        :ui        (assoc explorer/default-ui-state
                                     :group-prev {:server ["cursor-1"]}
                                     :nested-prev {"account|account-0001>server" ["server-0001-0005"]})
                        :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                        :child-sections {"account|account-0001>server" {:status "ready"}}
                        :db-rev    0})
    (with-redefs [state/start-count-jobs! (fn [] nil)
                  state/restart-expanded-child-section-jobs! (fn [] nil)
                  state/refresh-cache-metrics! (fn [] nil)]
      (state/register-db-listener!)
      (state/select-subject! "user-2"))
    (is (= "user-2" (:subject-id (state/query-state))))
    (is (= {:type :server :id "server-0001-0001"}
           (:selected-resource (state/query-state))))
    (is (= {} (get-in @state/!app [:ui :group-prev])))
    (is (= {} (get-in @state/!app [:ui :nested-prev])))
    (is (= {} (:child-sections @state/!app)))
    (is (= {} (:counts @state/!app)))))

(deftest select-permission-resets-pagination-and-persists-permission
  (install-runtime!)
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        (assoc explorer/default-ui-state
                                   :group-prev {:server ["cursor-1"]}
                                   :nested-prev {"account|account-0001>server" ["server-0001-0005"]})
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :child-sections {"account|account-0001>server" {:status "ready"}}
                      :db-rev    0})
  (with-redefs [state/start-count-jobs! (fn [] nil)
                state/restart-expanded-child-section-jobs! (fn [] nil)
                state/refresh-cache-metrics! (fn [] nil)]
    (state/register-db-listener!)
    (state/select-permission! :admin))
  (is (= :admin (:permission (state/query-state))))
  (is (= {} (get-in @state/!app [:ui :group-prev])))
  (is (= {} (get-in @state/!app [:ui :nested-prev])))
  (is (= {} (:child-sections @state/!app)))
  (is (= {} (:counts @state/!app))))

(deftest toggle-schema-flips-panel-visibility
  (state/toggle-schema!)
  (is (true? (get-in @state/!app [:ui :schema-expanded?])))
  (state/toggle-schema!)
  (is (false? (get-in @state/!app [:ui :schema-expanded?]))))

(deftest db-change-invalidates-counts-and-bumps-db-rev
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        (assoc explorer/default-ui-state
                                   :group-prev {:server ["cursor-1"]}
                                   :nested-prev {"account|account-0001>server"
                                                 ["server-0001-0020"]})
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :child-sections {"account|account-0001>server" {:status "ready"}}
                      :db-rev    4})
  (let [job-restarts (atom 0)]
    (with-redefs [state/start-count-jobs! (fn [] (swap! job-restarts inc))
                  state/restart-expanded-child-section-jobs! (fn [] nil)]
      (state/on-db-change!))
    (is (= 5 (:db-rev @state/!app)))
    (is (= {} (get-in @state/!app [:ui :group-prev])))
    (is (= {} (get-in @state/!app [:ui :nested-prev])))
    (is (= {} (:child-sections @state/!app)))
    (is (= explorer/default-count-state (:counts @state/!app)))
    (is (= 1 @job-restarts))))

(deftest db-change-during-seeding-skips-count-restart
  (reset! state/!app {:bootstrap (merge (ready-bootstrap)
                                   {:status :seeding})
                      :ui        (assoc explorer/default-ui-state
                                   :group-prev {:server ["cursor-1"]}
                                   :nested-prev {"account|account-0001>server"
                                                 ["server-0001-0020"]})
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :child-sections {"account|account-0001>server" {:status "ready"}}
                      :db-rev    4})
  (let [job-restarts (atom 0)]
    (with-redefs [state/start-count-jobs! (fn [] (swap! job-restarts inc))
                  state/restart-expanded-child-section-jobs! (fn [] (swap! job-restarts inc))]
      (state/on-db-change!))
    (is (= 5 (:db-rev @state/!app)))
    (is (= {} (get-in @state/!app [:ui :group-prev])))
    (is (= {} (get-in @state/!app [:ui :nested-prev])))
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

(deftest nested-relationship-page-renders-eacl-cache-provenance
  (let [requests (atom [])
        calls (atom 0)
        context {:cache-enabled? true}
        parent {:type :account :id "account-0001"}]
    (with-redefs [eacl/read-relationships
                  (fn [_ request]
                    (swap! requests conj request)
                    {:data []
                     :page-info {:end-cursor nil}
                     :cached? (> (swap! calls inc) 1)})
                  state/refresh-cache-metrics! (fn [] nil)]
      (let [first-page
            (#'state/read-child-relationships
             :acl context parent :server :account nil 20)
            repeated-page
            (#'state/read-child-relationships
             :acl context parent :server :account nil 20)]
        (is (= :miss (:cache-status first-page)))
        (is (= :hit (:cache-status repeated-page)))
        (is (= (first @requests) (second @requests)))
        (is (true? (:cache? (first @requests))))))))

(deftest child-section-total-finalization-preserves-visible-page-state
  (let [section-key "account|account-0001>server"
        job-id      "job-1"
        context     {:db-rev        0
                     :subject-id    "user-2"
                     :permission    :view
                     :cache-enabled? true
                     :query-generation 0
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
        :cache-status :hit
        :error       nil}
       false)
    (#'state/finalize-child-section-total!
     section-key job-id context 2000 "ready" [:miss])
    (is (= "ready" (get-in @state/!app [:child-sections section-key :status])))
    (is (= [{:type :server :id "server-0001-0001"}]
           (get-in @state/!app [:child-sections section-key :items])))
    (is (= 1 (get-in @state/!app [:child-sections section-key :page-start])))
    (is (= 1 (get-in @state/!app [:child-sections section-key :page-end])))
    (is (= "server-0001-0001"
           (get-in @state/!app [:child-sections section-key :next-cursor])))
    (is (= 2000 (get-in @state/!app [:child-sections section-key :total])))
    (is (= "ready" (get-in @state/!app [:child-sections section-key :total-status])))
    (is (= :hit (get-in @state/!app [:child-sections section-key :cache-status])))
    (is (= :miss
           (get-in @state/!app
                   [:child-sections section-key :total-cache-status])))
    (is (= 1000000 (get-in @state/!app [:child-sections section-key :time])))
    (is (nil? (get-in @state/!app [:child-sections section-key :job-id])))))

(deftest single-relation-child-section-pagination-uses-opaque-cursors
  (let [section-key "team|team-0001-01>server"
        parent      {:type :team :id "team-0001-01"}
        relation-def {:eacl.relation/relation-name :team}
        job-1       "job-1"
        job-2       "job-2"
        context-1   {:db-rev        0
                     :subject-id    "user-1"
                     :permission    :view
                     :cache-enabled? true
                     :query-generation 0
                     :parent-type   :team
                     :parent-id     "team-0001-01"
                     :resource-type :server
                     :cursor-token  nil}
        context-2   (assoc context-1 :cursor-token "cursor-2")
        page-1      (mapv (fn [n] {:type :server :id (str "server-page-1-" n)})
                          (range 1 21))
        page-2      (mapv (fn [n] {:type :server :id (str "server-page-2-" n)})
                          (range 1 21))
        responses   {nil        {:data   (mapv (fn [resource] {:resource resource}) page-1)
                                 :cursor "cursor-2"}
                     "cursor-2" {:data   (mapv (fn [resource] {:resource resource}) page-2)
                                 :cursor "cursor-3"}}
        calls       (atom [])]
    (reset! state/!runtime {:conn :conn :client :client})
    (reset! state/!app {:bootstrap (ready-bootstrap)
                        :ui        explorer/default-ui-state
                        :counts    explorer/default-count-state
                        :child-sections
                        {section-key (merge context-1
                                            {:status       "loading"
                                             :job-id       job-1
                                             :items        []
                                             :total        nil
                                             :total-status "loading"
                                             :page-start   0
                                             :page-end     0
                                             :next-cursor  nil
                                             :time         nil
                                             :error        nil})}
                        :db-rev    0})
    (with-redefs [js/setTimeout (fn [f _delay] (f))
                  state/client (fn [] :acl)
                  state/db (fn [] :db)
                  state/read-child-relationships
                  (fn [_ _ _ _ _ cursor-token limit]
                    (swap! calls conj [cursor-token limit])
                    (assoc (get responses cursor-token)
                           :cache-status :miss))
                  state/launch-single-relation-total-job! (fn [& _] nil)
                  state/start-expanded-child-sections-for-resources! (fn [& _] nil)
                  seed/->user (fn [subject-id] {:type :user :id subject-id})
                  explorer/child-permission-implied-by-parent? (fn [& _] true)
                  explorer/hydrate-resources (fn [_ resources] (vec resources))]
      (#'state/launch-single-relation-child-section-job!
       section-key
       context-1
       parent
       :server
       relation-def
       job-1)
      (let [entry-1 (get-in @state/!app [:child-sections section-key])]
        (is (= "ready" (:status entry-1)))
        (is (= "cursor-2" (:next-cursor entry-1)))
        (is (= 1 (:page-start entry-1)))
        (is (= 20 (:page-end entry-1)))
        (is (= (mapv :id page-1)
               (mapv :id (:items entry-1)))))
      (swap! state/!app
             (fn [app]
               (-> app
                   (assoc-in [:ui :nested-prev section-key] ["cursor-2"])
                   (assoc-in [:child-sections section-key]
                             (merge context-2
                                    {:status       "loading"
                                     :job-id       job-2
                                     :items        []
                                     :total        nil
                                     :total-status "loading"
                                     :page-start   0
                                     :page-end     0
                                     :next-cursor  nil
                                     :time         nil
                                     :error        nil})))))
      (#'state/launch-single-relation-child-section-job!
       section-key
       context-2
       parent
       :server
       relation-def
       job-2)
      (let [entry-2 (get-in @state/!app [:child-sections section-key])]
        (is (= [[nil explorer/resource-page-size]
                ["cursor-2" explorer/resource-page-size]]
               @calls))
        (is (= "ready" (:status entry-2)))
        (is (= "cursor-3" (:next-cursor entry-2)))
        (is (= 21 (:page-start entry-2)))
        (is (= 40 (:page-end entry-2)))
        (is (= (mapv :id page-2)
               (mapv :id (:items entry-2))))))))

(deftest child-page-timing-excludes-deferred-queue-delay
  (let [section-key "account|account-0001>server"
        parent {:type :account :id "account-0001"}
        relation-def {:eacl.relation/relation-name :account}
        job-id "job-1"
        context {:db-rev 0
                 :subject-id "super-user"
                 :permission :view
                 :cache-enabled? true
                 :query-generation 0
                 :parent-type :account
                 :parent-id "account-0001"
                 :resource-type :server
                 :cursor-token nil}
        clock (atom 0)
        scheduled (atom nil)]
    (reset! state/!runtime {:conn :conn :client :client})
    (reset! state/!app
            {:bootstrap (ready-bootstrap)
             :ui explorer/default-ui-state
             :counts explorer/default-count-state
             :child-sections
             {section-key
              (merge context
                     {:status "loading"
                      :job-id job-id
                      :items []
                      :total nil
                      :total-status "loading"
                      :page-start 0
                      :page-end 0
                      :next-cursor nil
                      :time nil
                      :error nil})}
             :db-rev 0})
    (with-redefs [js/setTimeout (fn [f _delay] (reset! scheduled f))
                  explorer/now-nanos (fn [] @clock)
                  state/client (fn [] :acl)
                  state/db (fn [] :db)
                  state/read-child-relationships
                  (fn [& _]
                    (reset! clock 105000000)
                    {:data [{:resource {:type :server :id "server-1"}}]
                     :cursor nil
                     :cache-status :hit})
                  state/start-expanded-child-sections-for-resources!
                  (fn [& _] nil)
                  seed/->user
                  (fn [subject-id] {:type :user :id subject-id})
                  explorer/child-permission-implied-by-parent?
                  (fn [& _] true)
                  explorer/hydrate-resources
                  (fn [_ resources] (vec resources))]
      (#'state/launch-single-relation-child-section-job!
       section-key context parent :server relation-def job-id)
      (is (some? @scheduled))
      (reset! clock 100000000)
      (@scheduled)
      (is (= 5000000
             (get-in @state/!app
                     [:child-sections section-key :time]))))))

(deftest selecting-resource-normalizes-permission-to-selected-resource-schema
  (let [{:keys [conn client] :as runtime} (seed/create-runtime)
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
    (reset! state/!runtime runtime)
    (reset! state/!app {:bootstrap (ready-bootstrap)
                        :ui        (assoc explorer/default-ui-state
                                     :permission :view)
                        :counts    explorer/default-count-state
                        :child-sections {}
                        :db-rev    0})
    (let [count-restarts (atom 0)
          child-restarts (atom 0)]
      (with-redefs [state/start-count-jobs! (fn [] (swap! count-restarts inc))
                    state/restart-expanded-child-section-jobs! (fn [] (swap! child-restarts inc))
                    state/refresh-cache-metrics! (fn [] nil)]
        (state/register-db-listener!)
        (state/select-resource! {:type :server :id "server-0001-0001"}))
      (is (= {:type :server :id "server-0001-0001"}
             (:selected-resource (state/query-state))))
      (is (= :admin (:permission (state/query-state))))
      (is (pos? @count-restarts))
      (is (pos? @child-restarts)))))

(deftest selecting-resource-does-not-restart-unrelated-query-jobs
  (let [{:keys [conn client] :as runtime} (seed/create-runtime)
        counts {:server {:status "done"
                         :count 60
                         :cache-status :hit
                         :job-id nil}}
        child-sections
        {"account|account-0001>server" {:status "ready"}}]
    (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
    (reset! state/!runtime runtime)
    (reset! state/!app {:bootstrap (ready-bootstrap)
                        :ui (assoc explorer/default-ui-state
                             :group-prev {:server ["cursor-1"]}
                             :nested-prev {"account|account-0001>server"
                                           ["server-0001-0020"]})
                        :counts counts
                        :child-sections child-sections
                        :cache-metrics {:status "idle"
                                        :data nil
                                        :edn "{}"
                                        :error nil}
                        :selection-rev 0
                        :db-rev 0})
    (let [count-restarts (atom 0)
          child-restarts (atom 0)]
      (with-redefs [state/start-count-jobs!
                    (fn [] (swap! count-restarts inc))
                    state/restart-expanded-child-section-jobs!
                    (fn [] (swap! child-restarts inc))
                    state/refresh-cache-metrics! (fn [] nil)]
        (state/register-db-listener!)
        (state/select-resource!
         {:type :server :id "server-0001-0001"}))
      (is (= {:type :server :id "server-0001-0001"}
             (:selected-resource (state/query-state))))
      (is (= 0 (:query-generation (state/query-state))))
      (is (= 0 (:db-rev @state/!app)))
      (is (= 1 (:selection-rev @state/!app)))
      (is (= {:server ["cursor-1"]}
             (get-in @state/!app [:ui :group-prev])))
      (is (= {"account|account-0001>server" ["server-0001-0020"]}
             (get-in @state/!app [:ui :nested-prev])))
      (is (= counts (:counts @state/!app)))
      (is (= child-sections (:child-sections @state/!app)))
      (is (zero? @count-restarts))
      (is (zero? @child-restarts)))))

(deftest datascript-is-the-live-query-option-authority
  (let [{:keys [conn]} (install-runtime!)]
    (swap! state/!app assoc :ui
           (assoc explorer/default-ui-state
                  :subject-id "stale-app-value"
                  :permission :stale
                  :cache-enabled? true))
    (transact-ui! conn
                  {:explorer.ui/subject-id "user-2"
                   :explorer.ui/permission :admin
                   :explorer.ui/cache-enabled? false
                   :explorer.ui/query-generation 7})
    (is (= {:subject-id "user-2"
            :permission :admin
            :selected-resource nil
            :cache-enabled? false
            :query-generation 7}
           (state/query-state)))
    (is (= "user-2"
           (get-in (state/view-state) [:ui :subject-id])))
    (is (= :admin
           (get-in (state/view-state) [:ui :permission])))))

(deftest cache-toggle-advances-generation-and-restarts-visible-jobs
  (install-runtime!)
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui explorer/default-ui-state
                      :counts {:server {:status "done"
                                        :count 20
                                        :job-id nil}}
                      :child-sections
                      {"account|account-0001>server" {:status "ready"}}
                      :cache-metrics {:status "idle"
                                      :data nil
                                      :edn "{}"
                                      :error nil}
                      :db-rev 0})
  (let [count-restarts (atom 0)
        child-restarts (atom 0)]
    (with-redefs [state/start-count-jobs!
                  (fn [] (swap! count-restarts inc))
                  state/restart-expanded-child-section-jobs!
                  (fn [] (swap! child-restarts inc))
                  state/refresh-cache-metrics! (fn [] nil)]
      (state/register-db-listener!)
      (state/set-cache-enabled! false))
    (is (false? (:cache-enabled? (state/query-state))))
    (is (= 1 (:query-generation (state/query-state))))
    (is (= 1 (:db-rev @state/!app)))
    (is (= {} (:child-sections @state/!app)))
    (is (= 1 @count-restarts))
    (is (= 1 @child-restarts))))

(deftest stale-count-publications-are-rejected-after-cache-toggle
  (install-runtime!)
  (let [old-context {:db-rev 0
                     :subject-id "user-1"
                     :permission :view
                     :cache-enabled? true
                     :query-generation 0}
        job-id "old-job"]
    (reset! state/!app {:bootstrap (ready-bootstrap)
                        :ui explorer/default-ui-state
                        :counts {:server {:status "loading"
                                          :count nil
                                          :job-id job-id}}
                        :child-sections {}
                        :cache-metrics {:status "idle"
                                        :data nil
                                        :edn "{}"
                                        :error nil}
                        :db-rev 0})
    (with-redefs [state/start-count-jobs! (fn [] nil)
                  state/restart-expanded-child-section-jobs! (fn [] nil)
                  state/refresh-cache-metrics! (fn [] nil)]
      (state/register-db-listener!)
      (state/set-cache-enabled! false))
    (is (false?
         (#'state/publish-count!
          :server
          job-id
          old-context
          {:status "done"
           :count 999
           :cache-status :hit}
          true)))
    (is (not= 999 (get-in @state/!app [:counts :server :count])))))

(deftest eviction-clears-the-native-cache-even-while-cache-is-disabled
  (let [{:keys [conn client] :as runtime} (seed/create-runtime)
        request {:subject (seed/->user "super-user")
                 :permission :view
                 :resource/type :account
                 :first 20}]
    (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
    (eacl/lookup-resources client request)
    (is (pos? (+ (:exact-entries (datascript/cache-stats client))
                 (:managed-entries (datascript/cache-stats client)))))
    (reset! state/!runtime runtime)
    (reset! state/!app (assoc (state/view-state
                              {:bootstrap (ready-bootstrap)
                               :ui explorer/default-ui-state
                               :counts explorer/default-count-state
                               :child-sections {}
                               :cache-metrics {:status "idle"
                                               :data nil
                                               :edn "{}"
                                               :error nil}
                               :db-rev 0})
                         :bootstrap (ready-bootstrap)))
    (with-redefs [state/start-count-jobs! (fn [] nil)
                  state/restart-expanded-child-section-jobs! (fn [] nil)
                  state/refresh-cache-metrics! (fn [] nil)]
      (state/register-db-listener!)
      (state/set-cache-enabled! false)
      (state/evict-cache!))
    (let [metrics (datascript/cache-stats client)]
      (is (zero? (+ (:exact-entries metrics)
                    (:managed-entries metrics))))
      (is (pos? (:expirations metrics))))
    (is (false? (:cache-enabled? (state/query-state))))
    (is (= 2 (:query-generation (state/query-state))))))

(deftest ui-only-datoms-do-not-change-authorization-answers
  (let [{:keys [conn client]} (seed/create-runtime)
        demand {:subject (seed/->user "user-1")
                :permission :view
                :resource (seed/->server "server-0001-0001")
                :cache? false}]
    (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
    (let [before (eacl/can? client demand)]
      (transact-ui! conn
                    {:explorer.ui/subject-id "user-2"
                     :explorer.ui/cache-enabled? false
                     :explorer.ui/query-generation 42})
      (is (= before (eacl/can? client demand)))
      (is (true? before)))))

(deftest metrics-refreshes-are-coalesced-and-render-provider-edn
  (install-runtime!)
  (let [scheduled (atom [])]
    (with-redefs [js/setTimeout
                  (fn [f _delay]
                    (swap! scheduled conj f)
                    1)]
      (state/refresh-cache-metrics!)
      (state/refresh-cache-metrics!))
    (is (= 1 (count @scheduled)))
    ((first @scheduled))
    (is (false? @state/!metrics-refresh-scheduled?))
    (is (= true
           (get-in @state/!app
                   [:cache-metrics :data :enabled?])))
    (is (number?
         (get-in @state/!app
                 [:cache-metrics :data :backend :exact-entries])))
    (is (re-find #":backend"
                 (get-in @state/!app [:cache-metrics :edn])))))

(deftest expanding-cache-section-refreshes-current-metrics
  (let [refreshes (atom 0)]
    (with-redefs [state/refresh-cache-metrics-now!
                  (fn [] (swap! refreshes inc))]
      (state/toggle-cache-section!)
      (is (true? (get-in @state/!app [:ui :cache-expanded?])))
      (is (= 1 @refreshes))
      (state/toggle-cache-section!)
      (is (false? (get-in @state/!app [:ui :cache-expanded?])))
      (is (= 1 @refreshes)))))

(deftest metrics-provider-errors-do-not-change-authorization
  (let [conn (seed/create-conn)
        client (seed/make-client conn)
        demand {:subject (seed/->user "user-1")
                :permission :view
                :resource (seed/->server "server-0001-0001")}
        runtime {:conn conn
                 :client client}]
    (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
    (let [before (eacl/can? client demand)]
      (reset! state/!runtime runtime)
      (with-redefs [datascript/cache-stats
                    (fn [_]
                      (throw (js/Error. "metrics unavailable")))]
        (let [snapshot (state/refresh-cache-metrics-now!)]
          (is (= "error" (:status snapshot)))
          (is (= "metrics unavailable" (:error snapshot)))
          (is (nil? (:data snapshot)))))
      (is (= before (eacl/can? client demand))))))

(deftest child-authorization-uses-detailed-provenance-and-conservative-aggregation
  (let [{:keys [conn client]} (seed/create-runtime)
        context {:cache-enabled? true}
        subject (seed/->user "user-1")
        resource (seed/->server "server-0001-0001")
        relationships [{:resource resource}]]
    (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
    (with-redefs [state/refresh-cache-metrics! (fn [] nil)]
      (is (= :miss
             (:cache-status
              (#'state/resource-authorization-result
               client context subject :view false resource))))
      (is (= :hit
             (:cache-status
              (#'state/resource-authorization-result
               client context subject :view false resource))))
      (let [{:keys [page-items cache-statuses]}
            (#'state/collect-page-items
             client
             context
             subject
             :view
             false
             []
             relationships
             [:miss])]
        (is (= [resource] page-items))
        (is (= [:miss :hit] cache-statuses))
        (is (= :miss
               (explorer/aggregate-cache-status
                context
                cache-statuses)))))))
