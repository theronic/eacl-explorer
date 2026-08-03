(ns eacl.explorer.state-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [eacl.core :as eacl]
            [eacl.datascript.core :as eacl-datascript]
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
                      :db-rev    0
                      :query-rev 0}))

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
                                   :group-pages {:server {:page-number 2
                                                         :page-options
                                                         {:first 20
                                                          :after "cursor-1"}}}
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
  (is (= {} (get-in @state/!app [:ui :group-pages])))
  (is (= {} (get-in @state/!app [:ui :nested-prev])))
  (is (= {} (:child-sections @state/!app)))
  (is (= explorer/default-count-state (:counts @state/!app))))

(deftest select-permission-resets-pagination-and-persists-permission
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        (assoc explorer/default-ui-state
                                   :group-pages {:server {:page-number 2
                                                         :page-options
                                                         {:first 20
                                                          :after "cursor-1"}}}
                                   :nested-prev {"account|account-0001>server" ["server-0001-0005"]})
                      :counts    {:server {:status "done" :count 12 :time "1.00ms" :job-id nil}}
                      :child-sections {"account|account-0001>server" {:status "ready"}}
                      :db-rev    0})
  (with-redefs [state/start-count-jobs! (fn [] nil)
                state/restart-expanded-child-section-jobs! (fn [] nil)]
    (state/select-permission! :admin))
  (is (= :admin (get-in @state/!app [:ui :permission])))
  (is (= {} (get-in @state/!app [:ui :group-pages])))
  (is (= {} (get-in @state/!app [:ui :nested-prev])))
  (is (= {} (:child-sections @state/!app)))
  (is (= explorer/default-count-state (:counts @state/!app))))

(deftest toggle-schema-flips-panel-visibility
  (state/toggle-schema!)
  (is (true? (get-in @state/!app [:ui :schema-expanded?])))
  (state/toggle-schema!)
  (is (false? (get-in @state/!app [:ui :schema-expanded?]))))

(deftest cache-toggle-refreshes-eacl-query-results
  (reset! state/!app {:bootstrap (ready-bootstrap)
                      :ui        explorer/default-ui-state
                      :counts    {:server {:status "done" :count 12}}
                      :child-sections {"account|account-0001>server" {:status "ready"}}
                      :db-rev    0
                      :query-rev 0})
  (let [count-restarts (atom 0)
        child-restarts (atom 0)]
    (with-redefs [state/start-count-jobs!
                  (fn [] (swap! count-restarts inc))
                  state/restart-expanded-child-section-jobs!
                  (fn [] (swap! child-restarts inc))]
      (state/set-cache-enabled! true)
      (is (true? (get-in @state/!app [:ui :cache-enabled?])))
      (is (= 1 (:query-rev @state/!app)))
      (is (= {} (:child-sections @state/!app)))
      (is (= explorer/default-count-state (:counts @state/!app)))
      (state/set-cache-enabled! true)
      (is (= 1 (:query-rev @state/!app)))
      (state/set-cache-enabled! false)
      (is (false? (get-in @state/!app [:ui :cache-enabled?])))
      (is (= 2 (:query-rev @state/!app))))
    (is (= 2 @count-restarts))
    (is (= 2 @child-restarts))))

(deftest clear-cache-evicts-eacl-cache-and-refreshes-query-results
  (let [{:keys [conn client]} (seed/create-runtime)
        _               (seed/install-foundation! conn client)
        expired         (atom [])
        count-restarts (atom 0)
        child-restarts (atom 0)]
    (reset! state/!runtime {:conn conn :client client})
    (reset! state/!app {:bootstrap (ready-bootstrap)
                        :ui        explorer/default-ui-state
                        :counts    {:server {:status "done" :count 12}}
                        :child-sections {"account|account-0001>server" {:status "ready"}}
                        :db-rev    0
                        :query-rev 3})
    (with-redefs [eacl-datascript/expire-cache!
                  (fn [acl] (swap! expired conj acl))
                  state/start-count-jobs!
                  (fn [] (swap! count-restarts inc))
                  state/restart-expanded-child-section-jobs!
                  (fn [] (swap! child-restarts inc))]
      (state/clear-cache!))
    (is (= [client] @expired))
    (is (= 4 (:query-rev @state/!app)))
    (is (= {} (:child-sections @state/!app)))
    (is (= explorer/default-count-state (:counts @state/!app)))
    (is (= 1 @count-restarts))
    (is (= 1 @child-restarts))))

(deftest nested-eacl-queries-follow-the-cache-toggle
  (let [relationship-queries (atom [])
        authorization-queries (atom [])]
    (with-redefs [eacl/read-relationships
                  (fn [_ query]
                    (swap! relationship-queries conj query)
                    {:data []})
                  eacl/can?
                  (fn [_ query]
                    (swap! authorization-queries conj query)
                    true)]
      (#'state/read-child-relationships
       :acl {:type :account :id "account-1"} :server :account nil 20 false)
      (#'state/read-child-relationships
       :acl {:type :account :id "account-1"} :server :account nil 20 true)
      (#'state/resource-authorized?
       :acl {:type :user :id "user-1"} :view false
       {:type :server :id "server-1"} false)
      (#'state/resource-authorized?
       :acl {:type :user :id "user-1"} :view false
       {:type :server :id "server-1"} true))
    (is (= [false true] (mapv :cache? @relationship-queries)))
    (is (= [false true] (mapv :cache? @authorization-queries)))))

(deftest top-level-pagination-uses-relay-directional-requests
  (state/next-group-page! :server "page-1-end")
  (is (= {:page-number 2
          :page-options {:first explorer/resource-page-size
                         :after "page-1-end"}}
         (get-in @state/!app [:ui :group-pages :server])))
  (state/prev-group-page! :server "page-2-start")
  (is (= {:page-number 1
          :page-options {:last explorer/resource-page-size
                         :before "page-2-start"}}
         (get-in @state/!app [:ui :group-pages :server])))
  (state/first-group-page! :server)
  (is (nil? (get-in @state/!app [:ui :group-pages :server]))))

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

(deftest single-relation-child-section-pagination-uses-opaque-cursors
  (let [section-key "team|team-0001-01>server"
        parent      {:type :team :id "team-0001-01"}
        relation-def {:eacl.relation/relation-name :team}
        job-1       "job-1"
        job-2       "job-2"
        context-1   {:db-rev        0
                     :subject-id    "user-1"
                     :permission    :view
                     :parent-type   :team
                     :parent-id     "team-0001-01"
                     :resource-type :server
                     :cursor-token  nil}
        context-2   (assoc context-1 :cursor-token "cursor-2")
        page-1      (mapv (fn [n] {:type :server :id (str "server-page-1-" n)})
                          (range 1 21))
        page-2      (mapv (fn [n] {:type :server :id (str "server-page-2-" n)})
                          (range 1 21))
        responses   {nil        {:data (mapv (fn [resource] {:resource resource}) page-1)
                                 :page-info {:end-cursor "cursor-2"
                                             :has-next-page? true}}
                     "cursor-2" {:data (mapv (fn [resource] {:resource resource}) page-2)
                                 :page-info {:end-cursor "cursor-3"
                                             :has-next-page? true}}}
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
                  (fn [_ _ _ _ cursor-token limit _cache?]
                    (swap! calls conj [cursor-token limit])
                    (get responses cursor-token))
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

(deftest selecting-resource-normalizes-permission-to-selected-resource-schema
  (let [{:keys [conn client]} (seed/create-runtime)
        server-viewless
        "definition user {}

         definition platform {
           relation user: user
           relation super_admin: user

           permission view = user
         }

         definition account {
           relation owner: user
           relation platform: platform

           permission admin = owner + platform->super_admin
           permission view = admin
         }

         definition team {
           relation account: account
           relation parent: team
           relation leader: user

           permission admin = account->admin + leader + parent->admin
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
