(ns eacl.explorer.seed
  (:require [datascript.core :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datascript.core :as datascript]
            [goog.string :as gstring]
            [goog.string.format]))

(def seed-version 2)
(def seed-marker-id "eacl-explorer/seed-state")
(def root-user-count 3)
(def default-seed-size 10000)

(def benchmark-seed-shape
  {:accounts-per-batch     2
   :teams-per-acct         4
   :vpcs-per-acct          2
   :servers-per-batch      500
   :servers-per-acct       2000
   :primary-owned-accounts 2})

(defn- requested-seed-profile
  []
  (let [search (some-> js/window .-location .-search)]
    (when (seq search)
      (some-> (js/URLSearchParams. search)
              (.get "seed-profile")
              not-empty
              keyword))))

(def default-seed-profile
  (or (requested-seed-profile) :benchmark))

(def seed-profiles
  {:benchmark (assoc benchmark-seed-shape
                :num-accounts          50
                :direct-shared-servers 0)
   :smoke     {:num-accounts           8
               :accounts-per-batch     4
               :teams-per-acct         3
               :vpcs-per-acct          2
               :servers-per-batch      25
               :servers-per-acct       60
               :direct-shared-servers  12
               :primary-owned-accounts 2}})

(def extra-schema
  {:eacl/id           {:db/unique :db.unique/identity}
   :account/name      {:db/index true}
   :team/name         {:db/index true}
   :vpc/name          {:db/index true}
   :server/name       {:db/index true}
   :seed/profile      {:db/index true}
   :seed/version      {}
   :seed/total-accounts {}
   :seed/total-teams    {}
   :seed/total-vpcs     {}
   :seed/total-servers {}
   :seed/total-users   {}
   :seed/next-account-n {}
   :seed/seed-runs      {}})

(def multipath-schema-dsl
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
   }")

(def ->user (partial spice-object :user))
(def ->team (partial spice-object :team))
(def ->server (partial spice-object :server))
(def ->platform (partial spice-object :platform))
(def ->account (partial spice-object :account))
(def ->vpc (partial spice-object :vpc))

(defn create-conn
  []
  (datascript/create-conn extra-schema))

(defn make-client
  [conn]
  (datascript/make-client conn
    {:entity->object-id     (fn [entity] (:eacl/id entity))
     :object-id->lookup-ref (fn [object-id] [:eacl/id object-id])}))

(defn create-runtime
  []
  (let [conn   (create-conn)
        client (make-client conn)]
    {:conn conn
     :client client}))

(defn normalize-profile
  [profile]
  (let [profile' (keyword (name (or profile default-seed-profile)))]
    (or (get seed-profiles profile')
        (throw (ex-info (str "Unknown seed profile " profile')
                 {:profile profile'})))
    profile'))

(defn profile-config
  [profile]
  (get seed-profiles (normalize-profile profile)))

(defn profile-totals
  [profile]
  (let [{:keys [num-accounts teams-per-acct vpcs-per-acct servers-per-acct direct-shared-servers]} (profile-config profile)]
    {:accounts num-accounts
     :teams    (* num-accounts teams-per-acct)
     :vpcs     (* num-accounts vpcs-per-acct)
     :servers  (+ (* num-accounts servers-per-acct)
                  direct-shared-servers)
     :users    (+ root-user-count
                  (* num-accounts (+ 1 teams-per-acct vpcs-per-acct)))}))

(def empty-totals
  {:accounts 0
   :teams    0
   :vpcs     0
   :servers  0
   :users    root-user-count})

(defn- fmt
  [template & args]
  (apply gstring/format template args))

(defn seed-state
  [db]
  (d/pull db [:eacl/id
              :seed/profile
              :seed/version
              :seed/total-accounts
              :seed/total-teams
              :seed/total-vpcs
              :seed/total-servers
              :seed/total-users
              :seed/next-account-n
              :seed/seed-runs]
    [:eacl/id seed-marker-id]))

(defn seeded?
  [db profile]
  (= {:seed/profile profile
      :seed/version seed-version}
     (select-keys (or (seed-state db) {})
       [:seed/profile :seed/version])))

(defn- existing-data?
  [db]
  (or (d/entid db [:eacl/id "platform"])
      (d/q '[:find ?entity .
             :where
             [?entity :server/name _]]
        db)))

(defn- pad
  [n]
  (fmt "%04d" n))

(defn- parse-account-number
  [account-id']
  (some->> (re-matches #"account-(\d+)" (str account-id'))
           second
           js/parseInt))

(defn- account-id [account-n] (str "account-" (pad account-n)))
(defn- account-name [account-n] (str "Account " (pad account-n)))
(defn- owner-id [account-n] (str "owner-" (pad account-n)))
(defn- team-id [account-n team-n] (fmt "team-%s-%02d" (pad account-n) team-n))
(defn- team-name [account-n team-n] (fmt "Team %02d / %s" team-n (account-name account-n)))
(defn- leader-id [account-n team-n] (fmt "leader-%s-%02d" (pad account-n) team-n))
(defn- vpc-id [account-n vpc-n] (fmt "vpc-%s-%02d" (pad account-n) vpc-n))
(defn- vpc-name [account-n vpc-n] (fmt "VPC %02d / %s" vpc-n (account-name account-n)))
(defn- vpc-admin-id [account-n vpc-n] (fmt "shared-admin-%s-%02d" (pad account-n) vpc-n))
(defn- server-id [account-n server-n] (fmt "server-%s-%04d" (pad account-n) server-n))
(defn- server-name [account-n server-n] (fmt "Server %04d / %s" server-n (account-name account-n)))
(defn- shared-server-id [server-n] (fmt "shared-server-%04d" server-n))
(defn- shared-server-name [server-n] (fmt "Shared Admin Server %04d" server-n))

(defn- relationship
  [subject relation resource]
  (->Relationship subject relation resource))

(defn- account-layout
  [account-n {:keys [teams-per-acct vpcs-per-acct]} server-count]
  (let [account-id' (account-id account-n)
        teams       (mapv (fn [team-n]
                            {:id        (team-id account-n team-n)
                             :leader-id (leader-id account-n team-n)
                             :name      (team-name account-n team-n)})
                      (range 1 (inc teams-per-acct)))
        vpcs        (mapv (fn [vpc-n]
                            {:id              (vpc-id account-n vpc-n)
                             :shared-admin-id (vpc-admin-id account-n vpc-n)
                             :name            (vpc-name account-n vpc-n)})
                      (range 1 (inc vpcs-per-acct)))]
    {:account-n account-n
     :account   {:id   account-id'
                 :name (account-name account-n)}
     :owner-id  (owner-id account-n)
     :server-count server-count
     :teams     teams
     :team-ids  (mapv :id teams)
     :vpcs      vpcs
     :vpc-ids   (mapv :id vpcs)}))

(defn- account-layouts-from-counts
  [account-start server-counts profile-config']
  (mapv (fn [offset server-count]
          (account-layout (+ account-start offset) profile-config' server-count))
    (range (count server-counts))
    server-counts))

(defn- account-layouts
  [{:keys [num-accounts] :as profile-config'}]
  (account-layouts-from-counts 1
    (repeat num-accounts (:servers-per-acct profile-config'))
    profile-config'))

(defn- root-entities-batch
  []
  {:phase        :foundation
   :label        "Installing root entities"
   :entity-count 4
   :tx-data      [{:db/id   -1
                   :eacl/id "platform"}
                  {:db/id   -2
                   :eacl/id "super-user"}
                  {:db/id   -3
                   :eacl/id "user-1"}
                  {:db/id   -4
                   :eacl/id "user-2"}]})

(defn- root-relationships-batch
  []
  {:phase          :foundation
   :label          "Installing root relationships"
   :relationships  [(relationship (->user "super-user") :super_admin (->platform "platform"))]})

(defn- structure-batch
  [layouts]
  {:phase         :structures
   :label         (fmt "Installing account structures %s-%s"
                    (:account-n (first layouts))
                    (:account-n (last layouts)))
   :entity-count  (reduce + 0
                   (map (fn [{:keys [teams vpcs]}]
                          (+ 2 (* 2 (count teams)) (* 2 (count vpcs))))
                     layouts))
   :tx-data       (vec
                    (mapcat
                     (fn [{:keys [account owner-id teams vpcs]}]
                       (concat
                        [{:eacl/id      (:id account)
                          :account/name (:name account)}
                         {:eacl/id owner-id}]
                        (mapcat (fn [{:keys [id leader-id name]}]
                                  [{:eacl/id id
                                    :team/name name}
                                   {:eacl/id leader-id}])
                          teams)
                        (mapcat (fn [{:keys [id shared-admin-id name]}]
                                  [{:eacl/id id
                                    :vpc/name name}
                                   {:eacl/id shared-admin-id}])
                          vpcs)))
                     layouts))
   :relationships (vec
                    (mapcat
                     (fn [{:keys [account owner-id teams vpcs]}]
                       (concat
                        [(relationship (->platform "platform") :platform (->account (:id account)))
                         (relationship (->user owner-id) :owner (->account (:id account)))]
                        (mapcat (fn [{:keys [id leader-id]}]
                                  [(relationship (->account (:id account)) :account (->team id))
                                   (relationship (->user leader-id) :leader (->team id))])
                          teams)
                        (mapcat (fn [{:keys [id shared-admin-id]}]
                                  [(relationship (->account (:id account)) :account (->vpc id))
                                   (relationship (->user shared-admin-id) :shared_admin (->vpc id))])
                          vpcs)))
                     layouts))})

(defn- server-batch
  [{:keys [account-n account team-ids vpc-ids]} server-ns]
  (let [team-count (count team-ids)
        vpc-count  (count vpc-ids)]
    {:phase         :servers
     :label         (fmt "Installing servers for account %s %s-%s"
                      account-n
                      (first server-ns)
                      (last server-ns))
     :servers-added (count server-ns)
     :tx-data       (vec
                      (for [server-n server-ns]
                        {:eacl/id     (server-id account-n server-n)
                         :server/name (server-name account-n server-n)}))
     :relationships (vec
                      (mapcat
                       (fn [server-n]
                         (let [server-id' (server-id account-n server-n)
                               team-id'   (nth team-ids (mod (dec server-n) team-count))
                               vpc-id'    (nth vpc-ids (mod (dec server-n) vpc-count))]
                           [(relationship (->account (:id account)) :account (->server server-id'))
                            (relationship (->team team-id') :team (->server server-id'))
                            (relationship (->vpc vpc-id') :vpc (->server server-id'))]))
                       server-ns))}))

(defn- direct-shared-server-batches
  [{:keys [direct-shared-servers servers-per-batch]}]
  (for [server-chunk (partition-all servers-per-batch
                       (range 1 (inc direct-shared-servers)))]
    {:phase         :shared-servers
     :label         (fmt "Installing direct shared servers %s-%s"
                      (first server-chunk)
                      (last server-chunk))
     :servers-added (count server-chunk)
     :tx-data       (vec
                      (for [server-n server-chunk]
                        {:eacl/id     (shared-server-id server-n)
                         :server/name (shared-server-name server-n)}))
     :relationships (vec
                      (for [server-n server-chunk]
                        (relationship (->user "super-user") :shared_admin
                          (->server (shared-server-id server-n)))))}))

(defn- deterministic-fixtures-batch
  [layouts {:keys [primary-owned-accounts]}]
  (let [primary-layouts (take primary-owned-accounts layouts)]
  {:phase         :fixtures
   :label         "Installing deterministic fixture relationships"
   :relationships (vec
                    (concat
                     (for [{:keys [account]} primary-layouts]
                       (relationship (->user "user-1") :owner (->account (:id account))))
                     (when-let [{:keys [account]} (first layouts)]
                       [(relationship (->user "user-2") :owner
                          (->account (:id account)))])))}))

(defn- totals->metadata
  [totals]
  {:seed/version        seed-version
   :seed/total-accounts (:accounts totals)
   :seed/total-teams    (:teams totals)
   :seed/total-vpcs     (:vpcs totals)
   :seed/total-servers  (:servers totals)
   :seed/total-users    (:users totals)})

(defn- metadata-entity
  [{:keys [seed/profile]
    :as metadata}]
  (cond-> {:eacl/id seed-marker-id}
    profile (assoc :seed/profile profile)
    true (merge (dissoc metadata :seed/profile))))

(defn- metadata-batch
  [label metadata]
  {:phase   :metadata
   :label   label
   :tx-data [(metadata-entity metadata)]})

(defn- seed-marker-batch
  [profile {:keys [num-accounts teams-per-acct vpcs-per-acct servers-per-acct direct-shared-servers]}]
  (let [totals (profile-totals profile)]
    (metadata-batch "Writing seed marker"
      (merge {:seed/profile      profile
              :seed/next-account-n (inc num-accounts)
              :seed/seed-runs      1}
        (totals->metadata totals)))))

(defn dataset-batches
  [profile]
  (let [profile'       (normalize-profile profile)
        config         (profile-config profile')
        layouts        (account-layouts config)
        structure-size (max 1 (long (:accounts-per-batch config)))
        server-size    (max 1 (long (:servers-per-batch config)))]
    (vec
     (concat
      [(root-entities-batch)
       (root-relationships-batch)]
      (for [layout-group (partition-all structure-size layouts)]
        (structure-batch layout-group))
      (for [{:keys [account-n] :as layout} layouts
            server-chunk (partition-all server-size
                           (range 1 (inc (:server-count layout))))]
        (server-batch layout server-chunk))
      (direct-shared-server-batches config)
      [(deterministic-fixtures-batch layouts config)
       (seed-marker-batch profile' config)]))))

(defn execute-batch!
  [conn client {:keys [tx-data relationships]}]
  (when (seq tx-data)
    (d/transact! conn tx-data))
  (when (seq relationships)
    (eacl/create-relationships! client relationships)))

(defn current-totals
  [db]
  (let [state (seed-state db)]
    {:accounts (or (:seed/total-accounts state) 0)
     :teams    (or (:seed/total-teams state) 0)
     :vpcs     (or (:seed/total-vpcs state) 0)
     :servers  (or (:seed/total-servers state) 0)
     :users    (or (:seed/total-users state) root-user-count)}))

(defn next-account-number
  [db]
  (or (:seed/next-account-n (seed-state db))
      (->> (d/q '[:find [?id ...]
                  :where
                  [?account :account/name _]
                  [?account :eacl/id ?id]]
             db)
           (keep parse-account-number)
           (reduce max 0)
           inc)))

(defn- seed-run-server-counts
  [servers-total]
  (loop [remaining (long servers-total)
         counts    []]
    (if (pos? remaining)
      (let [per-account (:servers-per-acct benchmark-seed-shape)
            server-count (min remaining per-account)]
        (recur (- remaining server-count) (conj counts server-count)))
      counts)))

(defn- interactive-seed-delta
  [server-counts]
  (let [account-count (count server-counts)]
    {:accounts account-count
     :teams    (* account-count (:teams-per-acct benchmark-seed-shape))
     :vpcs     (* account-count (:vpcs-per-acct benchmark-seed-shape))
     :servers  (reduce + 0 server-counts)
     :users    (* account-count
                  (+ 1
                     (:teams-per-acct benchmark-seed-shape)
                     (:vpcs-per-acct benchmark-seed-shape)))}))

(defn install-foundation!
  ([conn client]
   (install-foundation! conn client multipath-schema-dsl))
  ([conn client schema-string]
   (eacl/write-schema! client schema-string)
   (d/transact! conn
     [{:db/id   -1
       :eacl/id "platform"}
      {:db/id   -2
       :eacl/id "super-user"}
      {:db/id   -3
       :eacl/id "user-1"}
      {:db/id   -4
       :eacl/id "user-2"}
      (merge {:db/id -5
              :eacl/id seed-marker-id
              :seed/next-account-n 1
              :seed/seed-runs 0}
        (totals->metadata empty-totals))])
   (eacl/write-relationship! client :touch
     (->user "super-user") :super_admin (->platform "platform"))
   {:status :ready
    :totals (current-totals (d/db conn))}))

(defn seed-more-plan
  [db servers-total]
  (let [server-counts (seed-run-server-counts servers-total)
        account-start (next-account-number db)
        layouts       (account-layouts-from-counts account-start server-counts benchmark-seed-shape)
        structure-size (max 1 (long (:accounts-per-batch benchmark-seed-shape)))
        server-size   (max 1 (long (:servers-per-batch benchmark-seed-shape)))
        delta         (interactive-seed-delta server-counts)
        totals        (merge-with + (current-totals db) delta)
        metadata      (merge {:seed/next-account-n (+ account-start (count layouts))
                              :seed/seed-runs      (inc (or (:seed/seed-runs (seed-state db)) 0))}
                        (totals->metadata totals))]
    {:delta  delta
     :totals totals
     :batches
     (vec
      (concat
       (for [layout-group (partition-all structure-size layouts)]
         (structure-batch layout-group))
       (for [{:keys [account-n] :as layout} layouts
             server-chunk (partition-all server-size
                            (range 1 (inc (:server-count layout))))]
         (server-batch layout server-chunk))
       [(deterministic-fixtures-batch layouts benchmark-seed-shape)
        (metadata-batch "Updating seed metadata" metadata)]))}))

(defn install-schema+fixtures!
  ([conn client]
   (install-schema+fixtures! conn client {:seed/profile default-seed-profile}))
  ([conn client {:keys [seed/profile on-progress]}]
   (let [profile'    (normalize-profile profile)
         config      (profile-config profile')
         db          (d/db conn)
         on-progress (or on-progress (fn [_] nil))
         totals      (profile-totals profile')
         existing    (seed-state db)]
     (cond
       (seeded? db profile')
       (do
         (on-progress {:status :skipped
                       :profile profile'
                       :totals totals})
         {:status :skipped
          :profile profile'
          :seed/version seed-version
          :totals totals})

       (or existing (existing-data? db))
       (throw (ex-info "Database already contains data but does not match the expected seed marker."
                {:expected-profile profile'
                 :expected-version seed-version
                 :existing-state existing}))

       :else
       (let [batches        (dataset-batches profile')
             total-batches  (count batches)]
         (on-progress {:status :seeding
                       :profile profile'
                       :batch 0
                       :total-batches total-batches
                       :servers-completed 0
                       :servers-total (:servers totals)})
         (eacl/write-schema! client multipath-schema-dsl)
         (loop [remaining batches
                batch-n    0
                servers    0]
           (if-let [batch (first remaining)]
             (let [servers' (+ servers (long (or (:servers-added batch) 0)))]
               (execute-batch! conn client batch)
               (on-progress {:status           :seeding
                             :profile          profile'
                             :phase            (:phase batch)
                             :label            (:label batch)
                             :batch            (inc batch-n)
                             :total-batches    total-batches
                             :servers-completed servers'
                             :servers-total    (:servers totals)})
               (recur (next remaining) (inc batch-n) servers'))
             (do
               (on-progress {:status :ready
                             :profile profile'
                             :totals totals})
               {:status :seeded
                :profile profile'
                :seed/version seed-version
                :totals totals}))))))))
