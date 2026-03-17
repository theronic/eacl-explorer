(ns eacl.explorer.explorer
  (:require [clojure.string :as str]
            [datascript.core :as d]
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
(def quick-subject-order
  (zipmap (map :id quick-subjects) (range)))

(def resource-page-size 20)
(def user-page-size 20)
(def max-expansion-depth 4)

(defn normalize-identifier
  [value]
  (cond
    (nil? value)
    nil

    (string? value)
    (some-> value str/trim not-empty keyword)

    :else
    (try
      (some-> value name keyword)
      (catch :default _
        nil))))

(defn normalize-resource-type
  [value]
  (normalize-identifier value))

(defn normalize-permission-name
  [value]
  (normalize-identifier value))

(defn identifier-label
  [value]
  (cond
    (nil? value)
    nil

    (string? value)
    (some-> value str/trim not-empty)

    :else
    (or (try
          (some-> value name not-empty)
          (catch :default _
            nil))
        (str value))))

(defn- identifier-token
  [value]
  (or (identifier-label value) "unknown"))

(declare resource-type-sort-key)
(declare permission-sort-key)

(defn- canonical-resource-types
  [resource-types]
  (->> resource-types
       (keep normalize-resource-type)
       distinct
       (sort-by resource-type-sort-key)
       vec))

(defn permission-names->ui
  [permission-names]
  (->> permission-names
       (keep normalize-permission-name)
       distinct
       (sort-by permission-sort-key)
       vec))

(defn permissions-by-type->ui
  [permissions-by-type]
  (->> permissions-by-type
       (reduce-kv (fn [acc resource-type permission-names]
                    (if-let [resource-type' (normalize-resource-type resource-type)]
                      (update acc resource-type' (fnil into []) (permission-names->ui permission-names))
                      acc))
                  {})
       (map (fn [[resource-type permission-names]]
              [resource-type
               (permission-names->ui permission-names)]))
       (into {})))

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
   :schema-expanded?       false
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
  (let [resource-type' (normalize-resource-type resource-type)]
    [(get resource-type-order resource-type' 99)
     (or (identifier-label resource-type) "")]))

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
          normalize-permission-name))

(defn selected-resource
  [state]
  (some-> (or (get-in state [:ui :selected-resource])
              (:selected-resource state))
          (update :type normalize-resource-type)))

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
  (str (or (identifier-label type) "unknown") "|" id))

(defn parse-resource-key
  [key']
  (when-let [[_ type id] (re-matches #"([^|]+)\|(.*)" (str key'))]
    {:type (keyword type)
     :id   id}))

(defn resource-expanded?
  [state resource]
  (contains? (or (get-in state [:ui :expanded-resource-keys])
                 (:expanded-resource-keys state)
                 #{})
    (resource-key resource)))

(defn child-group-key
  [parent resource-type]
  (str (resource-key parent) ">" (identifier-token resource-type)))

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
  (cond
    (contains? quick-subject-order user-id)
    [0 (get quick-subject-order user-id) user-id]

    (str/starts-with? user-id "owner-")
    [1 0 user-id]

    (str/starts-with? user-id "shared-admin-")
    [2 0 user-id]

    (str/starts-with? user-id "leader-")
    [3 0 user-id]

    :else
    [4 0 user-id]))

(defn- permission-sort-key
  [permission]
  [(case (normalize-permission-name permission)
     :view 0
     :admin 1
     50)
   (or (identifier-label permission) "")])

(defn- resource-sort-key
  [resource]
  [(get resource-type-order (normalize-resource-type (:type resource)) 99)
   (or (:display-name resource) (:id resource))])

(defn- schema-data
  [acl]
  (eacl/read-schema acl))

(defn- schema-permissions
  [acl]
  (vec (or (:permissions (schema-data acl)) [])))

(defn- schema-resource-types
  [acl]
  (canonical-resource-types
   (map :eacl.permission/resource-type (schema-permissions acl))))

(defn- schema-permissions-by-type
  [acl]
  (permissions-by-type->ui
   (->> (schema-permissions acl)
        (group-by :eacl.permission/resource-type)
        (map (fn [[resource-type perms]]
               [resource-type
                (map :eacl.permission/permission-name perms)]))
        (into {}))))

(defn- permissions-by-type-from-db
  [db]
  (when db
    (->> (d/q '[:find ?resource-type ?permission-name
                :where
                [?permission :eacl.permission/resource-type ?resource-type]
                [?permission :eacl.permission/permission-name ?permission-name]]
              db)
         (group-by first)
         (map (fn [[resource-type rows]]
                [resource-type
                 (->> rows
                      (map second)
                      distinct
                      vec)]))
         (into {}))))

(defn- resource-types-from-db
  [db]
  (when db
    (let [resource-types (d/q '[:find [?resource-type ...]
                                :where
                                [_ :eacl.permission/resource-type ?resource-type]]
                              db)]
      (->> resource-types
           distinct
           (sort-by resource-type-sort-key)
           vec))))

(defn query-resource-types
  ([acl]
   (query-resource-types nil acl))
  ([db acl]
   (cond
     acl
     (schema-resource-types acl)

     (some? db)
     (let [resource-types (canonical-resource-types (resource-types-from-db db))]
       (if (seq resource-types)
         resource-types
         default-resource-types))

     :else
     default-resource-types)))

(defn permissions-by-type
  ([acl]
   (permissions-by-type nil acl))
  ([db acl]
   (cond
     acl
     (schema-permissions-by-type acl)

     (some? db)
     (permissions-by-type->ui (or (permissions-by-type-from-db db) {}))

     :else
     {})))

(defn- available-permissions-from-schema
  [db acl]
  (->> (permissions-by-type db acl)
       vals
       (apply concat)
       distinct
       (sort-by permission-sort-key)
       vec))

(defn available-permissions
  ([acl]
   (available-permissions-from-schema nil acl))
  ([acl _state]
   (available-permissions acl)))

(defn- permissions-for-resource-from-schema
  [db acl resource-type]
  (vec (get (permissions-by-type db acl)
            (normalize-resource-type resource-type)
            [])))

(defn permissions-for-resource
  ([acl resource-type]
   (permissions-for-resource-from-schema nil acl resource-type))
  ([acl _state resource-type]
   (permissions-for-resource acl resource-type)))

(defn selectable-permissions
  [db acl state]
  (if-let [resource (selected-resource state)]
    (if-let [resource-type (:type resource)]
      (permission-names->ui (permissions-for-resource-from-schema db acl resource-type))
      [])
    (permission-names->ui (available-permissions-from-schema db acl))))

(defn permission-available?
  ([acl resource-type permission]
   (permission-available? nil acl resource-type permission))
  ([db acl resource-type permission]
  (let [resource-type' (normalize-resource-type resource-type)
        permission'    (normalize-permission-name permission)]
    (and resource-type'
         permission'
         (contains? (set (permissions-for-resource-from-schema db acl resource-type'))
                    permission')))))

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

(defn child-permission-implied-by-parent?
  [acl parent-type child-type relation-name permission]
  (when (and acl parent-type child-type relation-name permission)
    (let [{:keys [relations permissions]} (schema-data acl)
          relation-targets               (into {}
                                          (map (fn [relation]
                                                 [[(:eacl.relation/resource-type relation)
                                                   (:eacl.relation/relation-name relation)]
                                                  (:eacl.relation/subject-type relation)]))
                                          relations)
          permission-rows                (group-by (juxt :eacl.permission/resource-type
                                                         :eacl.permission/permission-name)
                                                   permissions)]
      (loop [pending (list [child-type permission])
             seen    #{}]
        (when-let [[resource-type permission-name :as node] (first pending)]
          (if (contains? seen node)
            (recur (rest pending) seen)
            (let [rows      (get permission-rows node)
                  implied?  (some (fn [{:eacl.permission/keys [source-relation-name
                                                               target-type
                                                               target-name]}]
                                    (and (= relation-name source-relation-name)
                                         (= :permission target-type)
                                         (= permission target-name)
                                         (= parent-type
                                            (get relation-targets [resource-type source-relation-name]))))
                                  rows)
                  next-rows (keep (fn [{:eacl.permission/keys [source-relation-name
                                                               target-type
                                                               target-name]}]
                                    (when (and (= :self source-relation-name)
                                               (= :permission target-type))
                                      [resource-type target-name]))
                                  rows)]
              (if implied?
                true
                (recur (concat next-rows (rest pending))
                       (conj seen node))))))))))

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

(defn hydrate-resources
  [db resources]
  (hydrate-objects db resources))

(defn sort-resources
  [resources]
  (->> resources
       (sort-by resource-sort-key)
       vec))

(defn hydrate-and-sort-resources
  [db resources]
  (->> resources
       (hydrate-objects db)
       sort-resources))

(defn- known-user-id-stream
  [db acl]
  (->> (concat
        (when acl
          (loop [cursor-token nil
                 seen-cursors #{}
                 relationships []]
            (let [{:keys [data cursor]}
                  (eacl/read-relationships acl
                                           (cond-> {:subject/type :user
                                                    :limit        1000}
                                             cursor-token (assoc :cursor cursor-token)))
                  relationships' (into relationships data)
                  repeated?      (or (nil? cursor)
                                     (contains? seen-cursors cursor))]
              (if (and (= 1000 (count data))
                       (not repeated?))
                (recur cursor
                       (conj seen-cursors cursor)
                       relationships')
                (map (comp :id :subject) relationships')))))
        (keep (fn [{:keys [id]}]
                (when (d/entid db [:eacl/id id])
                  id))
              quick-subjects))
       distinct
       (sort-by user-sort-key)))

(defn paged-known-users
  [db _client acl state]
  (let [requested-page (max 0 (long (or (get-in state [:ui :user-page])
                                        (:user-page state)
                                        0)))
        started-at     (now-nanos)
        all-users      (vec (known-user-id-stream db acl))
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
    (str ":" (identifier-token permission) " is not defined for "
         (identifier-token resource-type) ".")
    "No permissions are defined in the current schema."))

(defn top-level-group-data
  [db acl state resource-type]
  (let [expanded?   (group-expanded? state resource-type)
        count-entry (get-in state [:counts resource-type] {:status "pending"})
        permission  (current-permission state)
        supported?  (permission-available? db acl resource-type permission)
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

(defn paginate-resources
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
        page-number   (child-group-page-number state section-key)
        permission    (current-permission state)
        supported?    (permission-available? db acl resource-type permission)
        cursor-token  (current-child-group-cursor state section-key)
        entry         (let [entry' (get-in state [:child-sections section-key])]
                        (when (= cursor-token (:cursor-token entry'))
                          entry'))
        status        (cond
                        (not supported?) "unavailable"
                        (some? entry) (:status entry)
                        :else "idle")
        total-status  (cond
                        (not supported?) "unavailable"
                        (= "error" status) "error"
                        (string? (:total-status entry)) (:total-status entry)
                        (number? (:total entry)) "ready"
                        (= "ready" status) "loading"
                        :else "loading")
        ready?        (= "ready" status)
        {:keys [items total page-start page-end next-cursor]}
        (if ready?
          {:items       (vec (or (:items entry) []))
           :total       (:total entry)
           :page-start  (:page-start entry)
           :page-end    (:page-end entry)
           :next-cursor (:next-cursor entry)}
          {:items []
           :total nil
           :page-start 0
           :page-end 0
           :next-cursor nil})
        rendered-items (when ready?
                         (->> items
                              (remove #(contains? visited (resource-key %)))
                              (mapv #(build-resource-node db acl state % (inc depth) visited))))
        loading-notice (when (and expanded?
                                  supported?
                                  (not ready?)
                                  (not= "error" status))
                         "Loading resources...")]
    (cond-> {:resource-type resource-type
             :section-key   section-key
             :parent        parent
             :parent-depth  depth
             :expanded?     expanded?
             :page-number   page-number
             :supported?    supported?
             :load-status   status
             :total-status  total-status
             :notice        (when-not supported?
                              (permission-notice permission resource-type))
             :total         (when ready? total)
             :time          (:time entry)}
      (= "error" status)
      (assoc :error (:error entry))

      expanded?
      (assoc :page-start  page-start
             :page-end    page-end
             :items       (or rendered-items [])
             :next-cursor next-cursor
             :notice      (or (:notice entry)
                              loading-notice
                              (when-not supported?
                                (permission-notice permission resource-type)))))))

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
  (mapv #(group-data db acl state %) (query-resource-types db acl)))

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
           (permissions-for-resource-from-schema db acl (:type resource)))}))
    {:resource nil}))

(defn schema-panel-data
  ([db acl]
   (schema-panel-data db acl nil))
  ([db acl _state]
   (let [{:keys [relations permissions]} (schema-data acl)
         distinct-permissions
         (->> permissions
              (map (juxt :eacl.permission/resource-type :eacl.permission/permission-name))
              distinct
              vec)
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
      :resource-count (count resource-kinds)
      :relation-count (count relations)
      :permission-count (count distinct-permissions)
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
