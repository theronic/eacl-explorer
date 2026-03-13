(ns eacl.explorer.explorer
  (:require [datascript.core :as d]
            [eacl.core :as eacl]
            [eacl.spicedb.consistency :as consistency]
            [eacl.explorer.seed :as seed]
            [goog.string :as gstring]
            [goog.string.format]))

(def default-resource-types [:account :team :vpc :server])
(def resource-type-order (zipmap default-resource-types (range)))
(def quick-subjects
  [{:id "super-user" :label "Super User"}
   {:id "user-1" :label "User 1"}
   {:id "user-2" :label "User 2"}])

(def resource-page-size 20)
(def user-page-size 20)
(def max-expansion-depth 4)

(def default-ui-state
  {:subject-id             "user-1"
   :permission             :view
   :selected-resource      nil
   :user-page              0
   :group-expanded         #{}
   :group-prev             {}
   :expanded-resource-keys #{}
   :expanded-section-keys  #{}
   :nested-prev            {}
   :show-schema?           false
   :schema-draft           seed/multipath-schema-dsl
   :seed-size-input        (str seed/default-seed-size)})

(defn count-state
  [resource-types]
  (into {}
    (map (fn [resource-type]
           [resource-type {:status "pending"
                           :count  nil
                           :time   nil
                           :job-id nil}]))
    resource-types))

(def default-count-state
  (count-state default-resource-types))

(defn- resource-type-sort-key
  [resource-type]
  [(get resource-type-order resource-type 99)
   (name resource-type)])

(defn now-nanos
  []
  (* 1000000.0 (.now js/performance)))

(defn human-duration
  [nanos]
  (when nanos
    (let [millis (/ (double nanos) 1000000.0)]
      (gstring/format "%.2fms" millis))))

(defn current-subject-id
  [state]
  (or (get-in state [:ui :subject-id])
      (:subject-id state)
      "user-1"))

(defn current-permission
  [state]
  (some-> (or (get-in state [:ui :permission])
              (:permission state))
          keyword))

(defn selected-resource
  [state]
  (or (get-in state [:ui :selected-resource])
      (:selected-resource state)))

(defn group-expanded?
  [state resource-type]
  (contains? (or (get-in state [:ui :group-expanded])
                 (:group-expanded state)
                 #{})
    resource-type))

(defn group-cursors
  [state resource-type]
  (vec (or (get-in state [:ui :group-prev resource-type])
           (get-in state [:group-prev resource-type])
           [])))

(defn group-page-number
  [state resource-type]
  (inc (count (group-cursors state resource-type))))

(defn current-group-cursor
  [state resource-type]
  (peek (group-cursors state resource-type)))

(defn resource-key
  [{:keys [type id]}]
  (str (name type) "|" id))

(defn resource-expanded?
  [state resource]
  (contains? (or (get-in state [:ui :expanded-resource-keys])
                 (:expanded-resource-keys state)
                 #{})
    (resource-key resource)))

(defn child-group-key
  [parent resource-type]
  (str (resource-key parent) ">" (name resource-type)))

(defn section-expanded?
  [state section-key]
  (contains? (or (get-in state [:ui :expanded-section-keys])
                 (:expanded-section-keys state)
                 #{})
    section-key))

(defn child-group-cursors
  [state section-key]
  (vec (or (get-in state [:ui :nested-prev section-key])
           (get-in state [:nested-prev section-key])
           [])))

(defn child-group-page-number
  [state section-key]
  (inc (count (child-group-cursors state section-key))))

(defn current-child-group-cursor
  [state section-key]
  (peek (child-group-cursors state section-key)))

(defn- user-sort-key
  [user-id]
  [(case user-id
     "super-user" 0
     "user-1" 1
     "user-2" 2
     99)
   user-id])

(defn- permission-sort-key
  [permission]
  [(case permission
     :view 0
     :admin 1
     50)
   (name permission)])

(defn- resource-sort-key
  [resource]
  [(get resource-type-order (:type resource) 99)
   (or (:display-name resource) (:id resource))])

(defn- schema-data
  [acl]
  (eacl/read-schema acl))

(defn query-resource-types
  [acl]
  (if-not acl
    default-resource-types
    (let [{:keys [permissions]} (schema-data acl)]
      (->> (map :eacl.permission/resource-type permissions)
           (remove nil?)
           distinct
           (sort-by resource-type-sort-key)
           vec))))

(defn permissions-by-type
  [acl]
  (let [{:keys [permissions]} (schema-data acl)]
    (->> permissions
         (group-by :eacl.permission/resource-type)
         (map (fn [[resource-type perms]]
                [resource-type
                 (->> perms
                      (map :eacl.permission/permission-name)
                      distinct
                      (sort-by permission-sort-key)
                      vec)]))
         (into {}))))

(defn available-permissions
  ([acl]
   (->> (permissions-by-type acl)
        vals
        (apply concat)
        distinct
        (sort-by permission-sort-key)
        vec))
  ([acl _state]
   (available-permissions acl)))

(defn permissions-for-resource
  ([acl resource-type]
   (vec (get (permissions-by-type acl) resource-type [])))
  ([acl _state resource-type]
   (permissions-for-resource acl resource-type)))

(defn permission-available?
  [acl resource-type permission]
  (and permission
       (contains? (set (permissions-for-resource acl resource-type)) permission)))

(defn relations-by-parent
  [acl]
  (let [{:keys [relations]} (schema-data acl)]
    (->> relations
         (group-by :eacl.relation/subject-type)
         (map (fn [[subject-type relation-defs]]
                [subject-type
                 (->> relation-defs
                      (group-by :eacl.relation/resource-type)
                      (map (fn [[resource-type defs]]
                             [resource-type
                              (vec (sort-by (juxt :eacl.relation/relation-name :eacl/id) defs))]))
                      (into {}))]))
         (into {}))))

(defn child-resource-types
  ([acl parent-type]
   (->> (get (relations-by-parent acl) parent-type)
        keys
        (sort-by resource-type-sort-key)
        vec))
  ([acl _state parent-type]
   (child-resource-types acl parent-type)))

(defn child-relation-defs
  ([acl parent-type resource-type]
   (vec (get-in (relations-by-parent acl) [parent-type resource-type] [])))
  ([acl _state parent-type resource-type]
   (child-relation-defs acl parent-type resource-type)))

(defn schema-source
  [db]
  (or (:eacl/schema-string (d/pull db [:eacl/schema-string] [:eacl/id "schema-string"]))
      ""))

(def object-builders
  {:account  seed/->account
   :platform seed/->platform
   :server   seed/->server
   :team     seed/->team
   :user     seed/->user
   :vpc      seed/->vpc})

(defn object-ref
  [resource-type object-id]
  (when (and resource-type object-id)
    (when-let [builder (get object-builders resource-type)]
      (builder object-id))))

(defn- hydrate-objects
  [db objects]
  (let [ids       (mapv :id objects)
        pulls     (if (seq ids)
                    (d/pull-many db
                      [:eacl/id :account/name :team/name :vpc/name :server/name]
                      (mapv (fn [id] [:eacl/id id]) ids))
                    [])
        entity-by-id
        (into {}
          (keep (fn [entity]
                  (when-let [entity-id (:eacl/id entity)]
                    [entity-id entity])))
          pulls)]
    (mapv (fn [{:keys [id] :as object}]
            (let [entity (get entity-by-id id)]
              (assoc (merge entity object)
                :display-name
                (or (:account/name entity)
                    (:team/name entity)
                    (:vpc/name entity)
                    (:server/name entity)
                    id))))
      objects)))

(defn hydrate-resource
  [db resource]
  (first (hydrate-objects db [resource])))

(defn- known-user-id-stream
  [db]
  (->> (concat
        (d/q '[:find [?user-id ...]
               :where
               [?rel :eacl.relationship/subject-type :user]
               [?rel :eacl.relationship/subject ?subject]
               [?subject :eacl/id ?user-id]]
          db)
        (keep (fn [{:keys [id]}]
                (when (d/entid db [:eacl/id id])
                  id))
          quick-subjects))
       distinct
       (sort-by user-sort-key)))

(defn paged-known-users
  [db _client _acl state]
  (let [requested-page (max 0 (long (or (get-in state [:ui :user-page])
                                        (:user-page state)
                                        0)))
        started-at     (now-nanos)
        all-users      (vec (known-user-id-stream db))
        total          (count all-users)
        max-page       (max 0 (long (quot (max 0 (dec total)) user-page-size)))
        effective-page (min requested-page max-page)
        offset         (* effective-page user-page-size)
        end            (min total (+ offset user-page-size))
        page-users     (subvec all-users offset end)
        elapsed        (- (now-nanos) started-at)]
    {:items      page-users
     :page       effective-page
     :total      total
     :time       elapsed
     :has-prev?  (pos? effective-page)
     :has-next?  (< end total)
     :page-start (if (seq page-users) (inc offset) 0)
     :page-end   end}))

(defn try-lookup-resources
  [db acl query]
  (let [started-at (now-nanos)]
    (try
      (let [{:keys [data cursor] :as result}
            (eacl/lookup-resources acl
              (assoc query :consistency consistency/fully-consistent))]
        (assoc result
          :items (hydrate-objects db data)
          :time  (- (now-nanos) started-at)))
      (catch :default ex
        {:items  []
         :data   []
         :cursor nil
         :error  (ex-message ex)
         :time   (- (now-nanos) started-at)}))))

(defn try-count-resources
  [acl query]
  (let [started-at (now-nanos)]
    (try
      (let [result (eacl/count-resources acl
                     (assoc query :consistency consistency/fully-consistent))]
        (assoc result :time (- (now-nanos) started-at)))
      (catch :default ex
        {:count  0
         :cursor nil
         :error  (ex-message ex)
         :time   (- (now-nanos) started-at)}))))

(defn- permission-notice
  [permission resource-type]
  (if permission
    (str ":" (name permission) " is not defined for " (name resource-type) ".")
    "No permissions are defined in the current schema."))

(defn top-level-group-data
  [db acl state resource-type]
  (let [expanded?   (group-expanded? state resource-type)
        count-entry (get-in state [:counts resource-type] {:status "pending"})
        permission  (current-permission state)
        supported?  (permission-available? acl resource-type permission)
        notice      (when-not supported?
                      (permission-notice permission resource-type))]
    (cond-> {:resource-type resource-type
             :expanded?     expanded?
             :supported?    supported?
             :notice        notice
             :count         (if supported? (:count count-entry) 0)
             :count-status  (if supported? (:status count-entry) "unavailable")
             :count-time    (:time count-entry)
             :page-number   (group-page-number state resource-type)}
      expanded?
      (merge
       (if supported?
         (let [result     (try-lookup-resources db acl
                            {:subject       (seed/->user (current-subject-id state))
                             :permission    permission
                             :resource/type resource-type
                             :cursor        (current-group-cursor state resource-type)
                             :limit         resource-page-size})
               item-count (count (:items result))
               start      (if (pos? item-count)
                            (inc (* resource-page-size (dec (group-page-number state resource-type))))
                            0)]
           {:page-start  (if (pos? item-count) start 0)
            :page-end    (+ (max 0 (dec start)) item-count)
            :items       (:items result)
            :next-cursor (:cursor result)
            :error       (:error result)
            :time        (:time result)})
         {:page-start  0
          :page-end    0
          :items       []
          :next-cursor nil
          :time        nil})))))

(declare build-resource-node)

(defn- dedupe-resources
  [resources]
  (:items
   (reduce (fn [{:keys [seen items]} resource]
             (let [key (resource-key resource)]
               (if (contains? seen key)
                 {:seen seen
                  :items items}
                 {:seen  (conj seen key)
                  :items (conj items resource)})))
     {:seen #{}
      :items []}
     resources)))

(defn- child-group-resources
  [acl parent relation-defs resource-type]
  (->> relation-defs
       (mapcat (fn [{relation-name :eacl.relation/relation-name}]
                 (eacl/read-relationships acl
                   {:subject/type      (:type parent)
                    :subject/id        (:id parent)
                    :resource/type     resource-type
                    :resource/relation relation-name})))
       (map :resource)
       dedupe-resources
       (sort-by resource-sort-key)
       vec))

(defn- authorized-child-group-resources
  [acl state parent relation-defs resource-type]
  (let [subject    (seed/->user (current-subject-id state))
        permission (current-permission state)]
    (if-not (permission-available? acl resource-type permission)
      []
      (->> (child-group-resources acl parent relation-defs resource-type)
           (filter #(eacl/can? acl subject permission % consistency/fully-consistent))
           vec))))

(defn- paginate-child-resources
  [resources cursor-token]
  (let [resources'  (vec resources)
        total       (count resources')
        index-by-id (zipmap (map :id resources') (range))
        start-index (if cursor-token
                      (if-let [idx (get index-by-id cursor-token)]
                        (inc idx)
                        total)
                      0)
        end-index   (min total (+ start-index resource-page-size))
        page-items  (subvec resources' start-index end-index)]
    {:items       page-items
     :total       total
     :page-start  (if (seq page-items) (inc start-index) 0)
     :page-end    end-index
     :next-cursor (when (< end-index total)
                    (:id (peek page-items)))}))

(defn- child-group-data
  [db acl state parent resource-type depth visited]
  (let [section-key   (child-group-key parent resource-type)
        expanded?     (section-expanded? state section-key)
        relation-defs (child-relation-defs acl state (:type parent) resource-type)
        page-number   (child-group-page-number state section-key)
        started-at    (now-nanos)
        permission    (current-permission state)
        supported?    (permission-available? acl resource-type permission)]
    (try
      (let [{:keys [items total page-start page-end next-cursor]}
            (if supported?
              (paginate-child-resources
               (authorized-child-group-resources acl state parent relation-defs resource-type)
               (current-child-group-cursor state section-key))
              {:items []
               :total 0
               :page-start 0
               :page-end 0
               :next-cursor nil})
            hydrated-items (hydrate-objects db items)
            rendered-items (->> hydrated-items
                                (remove #(contains? visited (resource-key %)))
                                (mapv #(build-resource-node db acl state % (inc depth) visited)))]
        (cond-> {:resource-type resource-type
                 :section-key   section-key
                 :parent        parent
                 :parent-depth  depth
                 :expanded?     expanded?
                 :page-number   page-number
                 :supported?    supported?
                 :notice        (when-not supported?
                                  (permission-notice permission resource-type))
                 :total         total
                 :time          (- (now-nanos) started-at)}
          expanded?
          (assoc :page-start  page-start
                 :page-end    page-end
                 :items       rendered-items
                 :next-cursor next-cursor)))
      (catch :default ex
        {:resource-type resource-type
         :section-key   section-key
         :parent        parent
         :parent-depth  depth
         :expanded?     expanded?
         :page-number   page-number
         :total         0
         :page-start    0
         :page-end      0
         :items         []
         :error         (ex-message ex)
         :time          (- (now-nanos) started-at)}))))

(defn- read-child-groups
  [db acl state parent depth visited]
  (try
    {:groups (->> (child-resource-types acl state (:type parent))
                  (map #(child-group-data db acl state parent % depth visited))
                  vec)}
    (catch :default ex
      {:groups []
       :error  (ex-message ex)})))

(defn build-resource-node
  [db acl state resource depth visited]
  (let [expanded?   (resource-expanded? state resource)
        visited'    (conj visited (resource-key resource))
        expandable? (boolean (seq (child-resource-types acl state (:type resource))))
        base        (assoc resource
                      :depth       depth
                      :expanded?   expanded?
                      :expandable? expandable?)]
    (if (and expanded? (< depth max-expansion-depth))
      (assoc base :children (read-child-groups db acl state resource depth visited'))
      base)))

(defn group-data
  [db acl state resource-type]
  (let [group (top-level-group-data db acl state resource-type)]
    (if (and (:expanded? group) (seq (:items group)))
      (update group :items
        #(mapv (fn [resource]
                 (build-resource-node db acl state resource 1 #{}))
           %))
      group)))

(defn resource-panel-data
  [db acl state]
  (mapv #(group-data db acl state %) (query-resource-types acl)))

(defn resource-node-data
  [db acl state resource-type resource-id depth]
  (when-let [resource (hydrate-resource db {:type resource-type :id resource-id})]
    (build-resource-node db acl state resource depth #{})))

(defn resource-section-data
  [db acl state parent-type parent-id resource-type depth]
  (when-let [parent (hydrate-resource db {:type parent-type :id parent-id})]
    (child-group-data db acl state parent resource-type depth #{(resource-key parent)})))

(defn resource-exists?
  [db {:keys [id]}]
  (boolean (d/entid db [:eacl/id id])))

(defn resource-detail-data
  [db acl state]
  (if-let [resource (selected-resource state)]
    (if-not (resource-exists? db resource)
      {:resource resource
       :error    (str "Resource " (:id resource) " could not be resolved.")}
      (let [resource-ref (object-ref (:type resource) (:id resource))
            hydrated     (hydrate-resource db resource)]
        {:resource hydrated
         :permissions
         (mapv (fn [permission]
                 (let [started-at (now-nanos)]
                   (try
                     (let [{:keys [data]}
                           (eacl/lookup-subjects acl
                             {:resource     resource-ref
                              :permission   permission
                              :subject/type :user
                              :consistency  consistency/fully-consistent})]
                       {:permission permission
                        :subjects   (->> data
                                         (sort-by :id)
                                         vec)
                        :time       (- (now-nanos) started-at)})
                     (catch :default ex
                       {:permission permission
                        :subjects   []
                        :error      (ex-message ex)
                        :time       (- (now-nanos) started-at)}))))
           (permissions-for-resource acl state (:type resource)))}))
    {:resource nil}))

(defn schema-panel-data
  ([db acl]
   (schema-panel-data db acl nil))
  ([db acl _state]
   (let [{:keys [relations permissions]} (schema-data acl)
         relation-targets (into {}
                           (map (fn [relation]
                                  [[(:eacl.relation/resource-type relation)
                                    (:eacl.relation/relation-name relation)]
                                   (:eacl.relation/subject-type relation)]))
                           relations)
         resource-kinds   (->> (concat
                                (mapcat (juxt :eacl.relation/resource-type :eacl.relation/subject-type) relations)
                                (map :eacl.permission/resource-type permissions))
                               distinct
                               (sort-by name))
         permission-node-id (fn [resource-type permission-name]
                              (str (name resource-type) ":" (name permission-name)))
         permission-nodes (->> permissions
                               (map (juxt :eacl.permission/resource-type :eacl.permission/permission-name))
                               distinct
                               (sort-by (fn [[resource-type permission-name]]
                                          [(name resource-type) (permission-sort-key permission-name)]))
                               (mapv (fn [[resource-type permission-name]]
                                       {:id            (permission-node-id resource-type permission-name)
                                        :label         (name permission-name)
                                        :kind          "permission"
                                        :resource-type (name resource-type)
                                        :type          (name resource-type)})))
         resource-nodes   (mapv (fn [resource-type]
                                  {:id    (name resource-type)
                                   :label (name resource-type)
                                   :kind  "resource"
                                   :type  (name resource-type)})
                            resource-kinds)
         relation-links   (->> relations
                               (sort-by (juxt :eacl.relation/resource-type
                                              :eacl.relation/relation-name
                                              :eacl.relation/subject-type))
                               (mapv (fn [relation]
                                       {:source (name (:eacl.relation/resource-type relation))
                                        :target (name (:eacl.relation/subject-type relation))
                                        :label  (name (:eacl.relation/relation-name relation))
                                        :kind   "relation"})))
         definition-links (->> permissions
                               (map (juxt :eacl.permission/resource-type :eacl.permission/permission-name))
                               distinct
                               (sort-by (fn [[resource-type permission-name]]
                                          [(name resource-type) (permission-sort-key permission-name)]))
                               (mapv (fn [[resource-type permission-name]]
                                       {:source (name resource-type)
                                        :target (permission-node-id resource-type permission-name)
                                        :label  "defines"
                                        :kind   "defines"})))
         permission-links (->> permissions
                               (keep (fn [{:eacl.permission/keys [resource-type
                                                                  permission-name
                                                                  source-relation-name
                                                                  target-type
                                                                  target-name]}]
                                       (let [source-id         (permission-node-id resource-type permission-name)
                                             related-type      (if (= :self source-relation-name)
                                                                 resource-type
                                                                 (get relation-targets [resource-type source-relation-name]))
                                             relation-target   (if (= :self source-relation-name)
                                                                 (get relation-targets [resource-type target-name])
                                                                 (get relation-targets [related-type target-name]))
                                             permission-target (permission-node-id (or related-type resource-type) target-name)]
                                         (cond
                                           (and (= target-type :permission) related-type)
                                           {:source source-id
                                            :target permission-target
                                            :label  (name source-relation-name)
                                            :kind   "permission"}

                                           (and (= target-type :relation) relation-target)
                                           {:source source-id
                                            :target (name relation-target)
                                            :label  (if (= :self source-relation-name)
                                                      (name target-name)
                                                      (str (name source-relation-name) "->" (name target-name)))
                                            :kind   "permission"}))))
                               vec)]
     {:schema-text (schema-source db)
      :nodes       (vec (concat resource-nodes permission-nodes))
      :links       (vec (concat relation-links definition-links permission-links))})))

(defn server-stat-data
  [state]
  (let [total    (get-in state [:bootstrap :totals :servers] 0)
        status   (get-in state [:bootstrap :status] :idle)
        progress (get-in state [:bootstrap :servers-completed] 0)
        target   (get-in state [:bootstrap :servers-target] total)]
    {:total        total
     :status       status
     :progress     progress
     :target       target}))
