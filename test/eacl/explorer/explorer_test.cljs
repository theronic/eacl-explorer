(ns eacl.explorer.explorer-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.set :as set]
            [datascript.core :as d]
            [eacl.core :as eacl]
            [eacl.explorer.explorer :as explorer]
            [eacl.explorer.seed :as seed]
            [eacl.explorer.support :as support]))

(defn- app-state
  ([ui]
   (app-state ui {}))
  ([ui {:keys [counts child-sections db-rev]
        :or   {counts explorer/default-count-state
               child-sections {}
               db-rev 0}}]
   {:ui             (merge explorer/default-ui-state ui)
    :counts         counts
    :child-sections child-sections
    :db-rev         db-rev}))

(deftest known-user-directory-pages-from-live-eacl-data
  (support/with-test-runtime* :smoke
    (fn [{:keys [conn client]}]
      (let [db                 (d/db conn)
            page-1             (explorer/paged-known-users db nil client (app-state {:user-page 0}))
            page-2             (explorer/paged-known-users db nil client (app-state {:user-page 1}))
            page-3             (explorer/paged-known-users db nil client (app-state {:user-page 2}))
            expected-page-2    (min explorer/user-page-size
                                    (max 0 (- (:total page-1) explorer/user-page-size)))
            expected-page-3    (min explorer/user-page-size
                                    (max 0 (- (:total page-1) (* 2 explorer/user-page-size))))]
        (is (= 20 (count (:items page-1))))
        (is (= expected-page-2 (count (:items page-2))))
        (is (= expected-page-3 (count (:items page-3))))
        (is (number? (:total page-1)))
        (is (= 1 (:page-start page-1)))
        (is (= 21 (:page-start page-2)))
        (is (= 41 (:page-start page-3)))
        (is (:has-next? page-1))
        (is (:has-next? page-2))
        (is (not (:has-next? page-3)))
        (is (string? (explorer/human-duration (:time page-1))))
        (is (not= (:items page-1) (:items page-2)))))))

(deftest known-user-directory-prioritizes-owners-and-shared-admins-after-seeding
  (let [{:keys [conn client]} (seed/create-runtime)]
    (seed/install-foundation! conn client)
    (doseq [batch (:batches (seed/seed-more-plan (d/db conn) 4500))]
      (seed/execute-batch! conn client batch))
    (let [db      (d/db conn)
          page-1  (explorer/paged-known-users db nil client (app-state {:user-page 0}))
          items   (:items page-1)]
      (is (= ["super-user" "user-1" "user-2"] (take 3 items)))
      (is (< (.indexOf items "owner-0001")
             (.indexOf items "leader-0001-01")))
      (is (< (.indexOf items "shared-admin-0001-01")
             (.indexOf items "leader-0001-01"))))))

(deftest resource-columns-render-against-foundation-only-runtime
  (let [{:keys [conn client]} (seed/create-runtime)]
    (seed/install-foundation! conn client)
    (let [db        (d/db conn)
          groups    (explorer/resource-panel-data db client (app-state {}))
          group-map (into {} (map (juxt :resource-type identity)) groups)]
      (is (= [:account :team :vpc :server] (mapv :resource-type groups)))
      (is (= "pending" (:count-status (:account group-map))))
      (is (= "pending" (:count-status (:server group-map))))
      (is (every? false? (map :expanded? groups))))))

(deftest resource-panel-pagination-uses-opaque-cursors
  (support/with-test-runtime* :smoke
    (fn [{:keys [conn client]}]
      (let [db             (d/db conn)
            first-page     (explorer/resource-panel-data db client
                                                         (app-state {:subject-id     "user-1"
                                                                     :permission     :view
                                                                     :group-expanded #{:server}}))
            server-group-1 (some #(when (= :server (:resource-type %)) %) first-page)
            next-cursor    (:next-cursor server-group-1)
            page-1-ids     (mapv :id (:items server-group-1))
            second-page    (explorer/resource-panel-data db client
                                                         (app-state {:subject-id     "user-1"
                                                                     :permission     :view
                                                                     :group-expanded #{:server}
                                                                     :group-prev     {:server [next-cursor]}}))
            server-group-2 (some #(when (= :server (:resource-type %)) %) second-page)
            page-2-ids     (mapv :id (:items server-group-2))]
        (is (string? next-cursor))
        (is (= 20 (count page-1-ids)))
        (is (= 20 (count page-2-ids)))
        (is (empty? (set/intersection (set page-1-ids) (set page-2-ids))))))))

(deftest nested-resource-groups-render-from-live-child-section-state
  (support/with-test-runtime* :smoke
    (fn [{:keys [conn client]}]
      (let [db             (d/db conn)
            account        {:type :account :id "account-0001"}
            section-key    (explorer/child-group-key account :server)
            page-items     (explorer/hydrate-resources db
                                                       (->> (eacl/read-relationships client
                                                                                     {:subject/type      :account
                                                                                      :subject/id        "account-0001"
                                                                                      :resource/type     :server
                                                                                      :resource/relation :account
                                                                                      :limit             20})
                                                            (map :resource)
                                                            (filter #(eacl/can? client (seed/->user "user-1") :view %))
                                                            vec))
            panel          (explorer/resource-panel-data db client
                                                         (app-state
                                                          {:subject-id             "user-1"
                                                           :permission             :view
                                                           :group-expanded         #{:account}
                                                           :expanded-resource-keys #{(explorer/resource-key account)}
                                                           :expanded-section-keys  #{section-key}}
                                                          {:child-sections
                                                           {section-key {:status      "ready"
                                                                         :cursor-token nil
                                                                         :items       page-items
                                                                         :total       60
                                                                         :page-start  1
                                                                         :page-end    20
                                                                         :next-cursor "server-0001-0020"
                                                                         :time        1000000}}}))
            account-group  (some #(when (= :account (:resource-type %)) %) panel)
            account-node   (some #(when (= "account-0001" (:id %)) %) (:items account-group))
            server-group   (some #(when (= :server (:resource-type %)) %) (get-in account-node [:children :groups]))]
        (is (= 60 (:total server-group)))
        (is (= 20 (count (:items server-group))))
        (is (= 1 (:page-start server-group)))
        (is (= 20 (:page-end server-group)))
        (is (= "server-0001-0001" (get-in server-group [:items 0 :id])))))))

(deftest nested-resource-groups-keep-page-items-while-total-is-still-loading
  (support/with-test-runtime* :smoke
    (fn [{:keys [conn client]}]
      (let [db             (d/db conn)
            account        {:type :account :id "account-0001"}
            section-key    (explorer/child-group-key account :server)
            page-items     (explorer/hydrate-resources db
                                                       (->> (eacl/read-relationships client
                                                                                     {:subject/type      :account
                                                                                      :subject/id        "account-0001"
                                                                                      :resource/type     :server
                                                                                      :resource/relation :account
                                                                                      :limit             20})
                                                            (map :resource)
                                                            vec))
            panel          (explorer/resource-panel-data db client
                                                         (app-state
                                                          {:subject-id             "super-user"
                                                           :permission             :view
                                                           :group-expanded         #{:account}
                                                           :expanded-resource-keys #{(explorer/resource-key account)}
                                                           :expanded-section-keys  #{section-key}}
                                                          {:child-sections
                                                           {section-key {:status       "ready"
                                                                         :cursor-token nil
                                                                         :items        page-items
                                                                         :total        nil
                                                                         :total-status "loading"
                                                                         :page-start   1
                                                                         :page-end     20
                                                                         :next-cursor  "server-0001-0020"
                                                                         :time         1000000}}}))
            account-group  (some #(when (= :account (:resource-type %)) %) panel)
            account-node   (some #(when (= "account-0001" (:id %)) %) (:items account-group))
            server-group   (some #(when (= :server (:resource-type %)) %) (get-in account-node [:children :groups]))]
        (is (= "loading" (:total-status server-group)))
        (is (nil? (:total server-group)))
        (is (= 20 (count (:items server-group))))
        (is (= 1 (:page-start server-group)))
        (is (= 20 (:page-end server-group)))
        (is (= "server-0001-0020" (:next-cursor server-group)))))))

(deftest child-permission-implication-detects-when-parent-permission-is-sufficient
  (support/with-test-runtime* :smoke
    (fn [{:keys [client]}]
      (is (true? (explorer/child-permission-implied-by-parent?
                  client :account :server :account :view)))
      (is (true? (explorer/child-permission-implied-by-parent?
                  client :account :server :account :admin)))
      (is (true? (explorer/child-permission-implied-by-parent?
                  client :team :server :team :view)))
      (is (true? (explorer/child-permission-implied-by-parent?
                  client :vpc :server :vpc :view)))
      (is (not (explorer/child-permission-implied-by-parent?
                client :account :team :account :view))))))

(deftest detail-data-preserves-selected-resource
  (support/with-test-runtime* :smoke
    (fn [{:keys [conn client]}]
      (let [db      (d/db conn)
            details (explorer/resource-detail-data db client
                                                   (app-state {:selected-resource {:type :server :id "server-0001-0001"}
                                                               :subject-id         "user-1"}))]
        (is (= "server-0001-0001" (get-in details [:resource :id])))
        (is (= :server (get-in details [:resource :type])))
        (is (seq (:permissions details)))))))

(deftest schema-panel-data-includes-source-and-permission-nodes
  (support/with-test-runtime* :smoke
    (fn [{:keys [conn client]}]
      (let [db         (d/db conn)
            panel-data (explorer/schema-panel-data db client)]
        (is (string? (:schema-text panel-data)))
        (is (re-find #"definition account" (:schema-text panel-data)))
        (is (some #(= "permission" (:kind %)) (:nodes panel-data)))
        (is (some #(= "defines" (:kind %)) (:links panel-data)))
        (is (some #(= "permission" (:kind %)) (:links panel-data)))))))

(deftest schema-changes-mark-unsupported-resource-permissions-without-dropping-groups
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
    (let [db           (d/db conn)
          permissions  (explorer/selectable-permissions db client (app-state {}))
          server-perms (get (explorer/permissions-by-type db client) :server)
          selected-perms (explorer/selectable-permissions db client
                           (app-state {:selected-resource {:type :server
                                                           :id   "server-0001-0001"}}))
          server-group (->> (explorer/resource-panel-data db client (app-state {:permission :view}))
                            (some #(when (= :server (:resource-type %)) %)))]
      (is (some #{:view} permissions))
      (is (= [:admin] server-perms))
      (is (= [:admin] selected-perms))
      (is server-group)
      (is (false? (:supported? server-group)))
      (is (= "unavailable" (:count-status server-group)))
      (is (= ":view is not defined for server."
             (:notice server-group))))))

(deftest conn-backed-selectable-permissions-track-schema-additions
  (let [{:keys [conn client]} (seed/create-runtime)
        server-operate
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
           permission view = admin + account->view + team->view + vpc->view + shared_admin
           permission operate = admin
         }"]
    (seed/install-schema+fixtures! conn client {:seed/profile :smoke})
    (eacl/write-schema! client server-operate)
    (let [db             (d/db conn)
          server-state   (app-state {:selected-resource {:type :server
                                                         :id   "server-0001-0001"}})
          server-perms   (get (explorer/permissions-by-type db client) :server)
          selected-perms (explorer/selectable-permissions db client server-state)
          all-perms      (explorer/selectable-permissions db client (app-state {}))]
      (is (= [:view :admin :operate] server-perms))
      (is (= [:view :admin :operate] selected-perms))
      (is (some #{:operate} all-perms)))))

(deftest normalize-identifier-coerces-keyword-like-values
  (is (= :server (explorer/normalize-resource-type :server)))
  (is (= :server (explorer/normalize-resource-type 'server)))
  (is (= :server (explorer/normalize-resource-type "server")))
  (is (nil? (explorer/normalize-resource-type nil)))
  (is (nil? (explorer/normalize-resource-type "")))
  (is (= :view (explorer/normalize-permission-name :view)))
  (is (= :view (explorer/normalize-permission-name 'view)))
  (is (= :view (explorer/normalize-permission-name "view")))
  (is (nil? (explorer/normalize-permission-name nil))))

(deftest query-helpers-normalize-ui-facing-identifiers
  (with-redefs [explorer/resource-types-from-db (fn [_]
                                                  [:server "team" 'account nil ""])
                explorer/schema-data (fn [_]
                                       {:permissions [{:eacl.permission/resource-type :server
                                                       :eacl.permission/permission-name "view"}
                                                      {:eacl.permission/resource-type "server"
                                                       :eacl.permission/permission-name 'admin}
                                                      {:eacl.permission/resource-type 'account
                                                       :eacl.permission/permission-name :view}
                                                      {:eacl.permission/resource-type 'team
                                                       :eacl.permission/permission-name nil}
                                                      {:eacl.permission/resource-type nil
                                                       :eacl.permission/permission-name :admin}]})]
    (is (= [:account :team :server]
           (explorer/query-resource-types :db :acl)))
    (is (= {:account [:view]
            :server  [:view :admin]
            :team    []}
           (explorer/permissions-by-type nil :acl)))
    (is (= [:view :admin]
           (explorer/selectable-permissions nil :acl {:ui {}})))
    (is (= [:view :admin]
           (explorer/selectable-permissions nil :acl
                                            {:ui {:selected-resource {:type "server"
                                                                      :id   "server-1"}}})))
    (is (= []
           (explorer/selectable-permissions nil :acl
                                            {:ui {:selected-resource {:type nil
                                                                      :id   "server-1"}}})))))

(deftest schema-metadata-falls-back-to-authoritative-client-view
  (with-redefs [explorer/resource-types-from-db (fn [_] [])
                explorer/permissions-by-type-from-db (fn [_] {})
                explorer/schema-data (fn [_]
                                       {:permissions [{:eacl.permission/resource-type :server
                                                       :eacl.permission/permission-name :view}
                                                      {:eacl.permission/resource-type :server
                                                       :eacl.permission/permission-name :admin}
                                                      {:eacl.permission/resource-type :team
                                                       :eacl.permission/permission-name :view}]})]
    (is (= [:team :server]
           (explorer/query-resource-types :db :acl)))
    (is (= {:server [:view :admin]
            :team   [:view]}
           (explorer/permissions-by-type :db :acl)))
    (is (= [:view :admin]
           (explorer/selectable-permissions :db :acl
                                            {:ui {:selected-resource {:type :server
                                                                      :id   "server-1"}}})))
    (is (= [:view :admin]
           (explorer/selectable-permissions :db :acl {:ui {}})))))

(deftest invalid-schema-reports-missing-permission-reference
  (let [{:keys [client]} (seed/create-runtime)
        invalid-schema
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

           permission view = admin + account->view + team->view + vpc->view + shared_admin
         }"]
    (try
      (eacl/write-schema! client invalid-schema)
      (is false "Expected invalid schema to throw")
      (catch :default ex
        (is (re-find #"Permission server/view references non-existent permission: admin"
                     (ex-message ex)))
        (is (= :invalid-self-permission
               (get-in (ex-data ex) [:errors 0 :type])))))))
