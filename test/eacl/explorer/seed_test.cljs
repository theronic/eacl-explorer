(ns eacl.explorer.seed-test
  (:require [cljs.test :refer-macros [deftest is]]
            [datascript.core :as d]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.explorer.seed :as seed]
            [eacl.explorer.support :as support]))

(deftest explorer-client-inherits-managed-datascript-coherence
  (let [client (seed/make-client (seed/create-conn))]
    (is (= :managed
           (get-in client [:opts :coherence-authority])))
    (is (= :mutation
           (get-in client [:opts :proof-mode])))))

(deftest install-schema-and-fixtures-uses-smoke-profile
  (support/with-test-runtime* :smoke
    (fn [{:keys [conn client]}]
      (let [db                (d/db conn)
            seed-state        (seed/seed-state db)
            account-page      (eacl/lookup-resources client
                                                     {:subject       (seed/->user "user-1")
                                                      :permission    :admin
                                                      :resource/type :account
                                                      :first         20})
            account-view-page (eacl/lookup-resources client
                                                     {:subject       (seed/->user "super-user")
                                                      :permission    :view
                                                      :resource/type :account
                                                      :first         20})
            server-page       (eacl/lookup-resources client
                                                     {:subject       (seed/->user "user-1")
                                                      :permission    :view
                                                      :resource/type :server
                                                      :first         20})]
        (is (= :smoke (:seed/profile seed-state)))
        (is (= seed/seed-version (:seed/version seed-state)))
        (is (= 8 (d/q '[:find (count ?account) .
                        :where
                        [?account :account/name _]]
                 db)))
        (is (= 2 (count (:data account-page))))
        (is (= 8 (count (:data account-view-page))))
        (is (= 20 (count (:data server-page))))
        (is (true? (get-in server-page [:page-info :has-next-page?])))
        (is (string? (get-in server-page [:page-info :end-cursor])))))))

(deftest install-schema-and-fixtures-skips-when-seed-marker-matches
  (let [{:keys [conn client]} (seed/create-runtime)
        first-pass            (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
        first-count           (d/q '[:find (count ?server) .
                                     :where
                                     [?server :server/name _]]
                              (d/db conn))
        second-pass           (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
        second-count          (d/q '[:find (count ?server) .
                                     :where
                                     [?server :server/name _]]
                              (d/db conn))]
    (is (= :seeded (:status first-pass)))
    (is (= :skipped (:status second-pass)))
    (is (= first-count second-count))))

(deftest benchmark-profile-targets-one-hundred-thousand-servers
  (let [{:keys [servers]} (seed/profile-totals :benchmark)
        {:keys [num-accounts servers-per-acct primary-owned-accounts]}
        (seed/profile-config :benchmark)]
    (is (= 100000 servers))
    (is (= 50 num-accounts))
    (is (= 2000 servers-per-acct))
    (is (= 4 primary-owned-accounts))))

(deftest schema-presets-expose-matched-non-recursive-and-recursive-fixtures
  (is (= [:non-recursive :recursive]
         (mapv :id seed/schema-presets)))
  (is (= ["Non-recursive" "Recursive"]
         (mapv :label seed/schema-presets)))
  (is (not (re-find #"relation parent: account"
                    seed/multipath-schema-dsl)))
  (is (re-find #"relation parent: account"
               seed/recursive-schema-dsl))
  (is (re-find #"relation parent: server"
               seed/recursive-schema-dsl)))

(deftest foundation-installs-schema-and-root-subjects-without-seeding-domain-data
  (let [{:keys [conn client]} (seed/create-runtime)]
    (seed/install-foundation! conn client)
    (let [db         (d/db conn)
          seed-state (seed/seed-state db)]
      (is (= seed/empty-totals (seed/current-totals db)))
      (is (= 1 (:seed/next-account-n seed-state)))
      (is (= 0 (:seed/seed-runs seed-state)))
      (is (= 1 (count (:data (eacl/read-relationships client
                               {:subject/type      :user
                                :subject/id        "super-user"
                                :resource/type     :platform
                                :resource/id       "platform"
                                :resource/relation :super_admin})))))
      (is (zero? (or (d/q '[:find (count ?server) .
                            :where
                            [?server :server/name _]]
                     db)
                     0))))))

(deftest shared-runtime-reuses-browser-connection-while-create-runtime-stays-isolated
  (let [shared-1 seed/shared-runtime
        shared-2 seed/shared-runtime
        fresh-1  (seed/create-runtime)
        fresh-2  (seed/create-runtime)]
    (is (identical? (:conn shared-1) (:conn shared-2)))
    (is (identical? (:client shared-1) (:client shared-2)))
    (is (not (identical? (:conn fresh-1) (:conn fresh-2))))
    (is (not (identical? (:client fresh-1) (:client fresh-2))))))

(deftest runtime-cache-controls-target-the-clients-native-cache
  (let [{:keys [conn client]} (seed/create-runtime)
        request {:subject (seed/->user "super-user")
                 :permission :view
                 :resource/type :account
                 :first 20}]
    (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
    (is (false? (:cached? (eacl/lookup-resources client request))))
    (is (pos? (+ (:exact-entries (datascript/cache-stats client))
                 (:managed-entries (datascript/cache-stats client)))))
    (is (true? (:cached? (eacl/lookup-resources client request))))
    (datascript/expire-cache! client)
    (is (zero? (+ (:exact-entries (datascript/cache-stats client))
                  (:managed-entries (datascript/cache-stats client)))))
    (is (false? (:cached? (eacl/lookup-resources client request))))))

(deftest read-relationships-honors-relay-pagination-for-anchored-queries
  (support/with-test-runtime* :smoke
    (fn [{:keys [client]}]
      (let [query {:subject/type      :account
                   :subject/id        "account-0001"
                   :resource/type     :team
                   :resource/relation :account
                   :first             2}
            first-response (eacl/read-relationships client query)
            repeated-response (eacl/read-relationships client query)
            {page-1 :data page-info :page-info} first-response
            cursor (:end-cursor page-info)
            {page-2 :data}
            (eacl/read-relationships client
                                     {:subject/type      :account
                                      :subject/id        "account-0001"
                                      :resource/type     :team
                                      :resource/relation :account
                                      :after             cursor
                                      :first             2})
            page-1-ids (mapv (comp :id :resource) page-1)
            page-2-ids (mapv (comp :id :resource) page-2)]
        (is (= 2 (count page-1)))
        (is (= 1 (count page-2)))
        (is (= ["team-0001-01" "team-0001-02"] page-1-ids))
        (is (= ["team-0001-03"] page-2-ids))
        (is (true? (:has-next-page? page-info)))
        (is (false? (:cached? first-response)))
        (is (true? (:cached? repeated-response)))
        (is (string? cursor))))))

(deftest seed-more-plan-appends-servers-and-advances-account-counters
  (let [{:keys [conn client]} (seed/create-runtime)]
    (seed/install-foundation! conn client)
    (let [{:keys [batches totals]} (seed/seed-more-plan (d/db conn) 2500)]
      (doseq [batch batches]
        (seed/execute-batch! conn client batch))
      (let [db         (d/db conn)
            seed-state (seed/seed-state db)]
        (is (= 2500 (:servers totals)))
        (is (= 2500 (:seed/total-servers seed-state)))
        (is (= 3 (:seed/next-account-n seed-state)))
        (is (= 1 (:seed/seed-runs seed-state)))))))
