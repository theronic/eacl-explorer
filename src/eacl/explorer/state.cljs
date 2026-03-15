(ns eacl.explorer.state
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [eacl.core :as eacl]
            [eacl.explorer.explorer :as explorer]
            [eacl.explorer.seed :as seed]))

(def default-bootstrap
  {:status            :booting
   :totals            seed/empty-totals
   :servers-target    0
   :servers-completed 0
   :batch             0
   :total-batches     0
   :label             nil
   :seed-error        nil
   :schema-error      nil})

(defn- initial-app-state
  []
  {:bootstrap default-bootstrap
   :ui        explorer/default-ui-state
   :counts    explorer/default-count-state
   :child-sections {}
   :db-rev    0})

(defonce !runtime (atom nil))
(defonce !app (atom (initial-app-state)))
(defonce listener-key (keyword (str "eacl-explorer-" (random-uuid))))

(defn runtime
  []
  @!runtime)

(defn db
  []
  (some-> @!runtime :conn d/db))

(defn client
  []
  (:client @!runtime))

(defn ready?
  []
  (not= :booting (get-in @!app [:bootstrap :status])))

(defn- seeding?
  []
  (= :seeding (get-in @!app [:bootstrap :status])))

(defn- count-jobs-enabled?
  []
  (and (ready?)
       (not (seeding?))))

(defn- read-session
  [key fallback]
  (try
    (or (.getItem js/sessionStorage key) fallback)
    (catch :default _
      fallback)))

(defn- write-session!
  [key value]
  (try
    (.setItem js/sessionStorage key value)
    (catch :default _
      nil)))

(defn- remove-session!
  [key]
  (try
    (.removeItem js/sessionStorage key)
    (catch :default _
      nil)))

(defn- load-ui-state
  []
  (let [stored-permission (read-session "eacl-explorer-permission" nil)]
    (assoc explorer/default-ui-state
           :subject-id (read-session "eacl-explorer-subject-id" (:subject-id explorer/default-ui-state))
           :permission (or (some-> stored-permission explorer/normalize-permission-name)
                           (:permission explorer/default-ui-state)))))

(defn- persist-ui!
  [app]
  (write-session! "eacl-explorer-subject-id" (explorer/current-subject-id app))
  (if-let [permission (explorer/current-permission app)]
    (write-session! "eacl-explorer-permission" (name permission))
    (remove-session! "eacl-explorer-permission")))

(defn- reset-navigation
  [ui]
  (assoc ui
         :group-prev {}
         :nested-prev {}))

(defn- resource-types
  []
  (if-let [acl (client)]
    (explorer/query-resource-types (db) acl)
    explorer/default-resource-types))

(defn- available-permissions
  [app]
  (if-let [acl (client)]
    (explorer/selectable-permissions (db) acl app)
    nil))

(defn- normalize-ui
  [app]
  (let [permissions (available-permissions app)
        current     (explorer/current-permission app)
        normalized  (when (some? permissions)
                      (or (when (some #{current} permissions) current)
                          (first permissions)
                          nil))]
    (if (or (nil? permissions)
            (= current normalized))
      app
      (-> app
          (update :ui reset-navigation)
          (assoc-in [:ui :permission] normalized)))))

(defn- current-count-context
  [app]
  {:db-rev     (:db-rev app)
   :subject-id (explorer/current-subject-id app)
   :permission (explorer/current-permission app)})

(defn- same-job-context?
  [app resource-type job-id context]
  (and (= job-id (get-in app [:counts resource-type :job-id]))
       (= context (current-count-context app))))

(defn invalidate-counts!
  []
  (swap! !app assoc :counts (explorer/count-state (resource-types))))

(defn invalidate-child-sections!
  []
  (swap! !app assoc :child-sections {}))

(declare start-count-jobs!
         ensure-section-key-loaded!
         restart-expanded-child-section-jobs!)

(defn on-db-change!
  []
  (swap! !app
         (fn [app]
           (-> app
               normalize-ui
               (update :db-rev (fnil inc 0)))))
  (when (ready?)
    (invalidate-child-sections!)
    (invalidate-counts!)
    (when-not (seeding?)
      (start-count-jobs!))
    (when-not (seeding?)
      (restart-expanded-child-section-jobs!))))

(defn register-db-listener!
  []
  (when-let [conn (:conn @!runtime)]
    (d/listen! conn listener-key
               (fn [_] (on-db-change!)))))

(defn- publish-count!
  [resource-type job-id context snapshot final?]
  (let [updated? (atom false)]
    (swap! !app
           (fn [app]
             (if (same-job-context? app resource-type job-id context)
               (do
                 (reset! updated? true)
                 (assoc-in app [:counts resource-type]
                           (assoc snapshot :job-id (when-not final? job-id))))
               app)))
    @updated?))

(defn- launch-count-job!
  [resource-type]
  (when-let [acl (client)]
    (let [context    (current-count-context @!app)
          permission (:permission context)]
      (if-not (explorer/permission-available? (db) acl resource-type permission)
        (swap! !app assoc-in [:counts resource-type]
               {:status "unavailable"
                :count  0
                :time   nil
                :job-id nil})
        (let [job-id  (str (random-uuid))]
          (swap! !app assoc-in [:counts resource-type]
                 {:status "loading"
                  :count  nil
                  :time   nil
                  :job-id job-id})
          (letfn [(step [cursor-token total limit elapsed seen-cursors]
                    (when (same-job-context? @!app resource-type job-id context)
                      (let [{:keys [count error cursor time]}
                            (explorer/try-count-resources acl
                                                          (cond-> {:subject       (seed/->user (:subject-id context))
                                                                   :permission    (:permission context)
                                                                   :resource/type resource-type
                                                                   :limit         limit}
                                                            cursor-token (assoc :cursor cursor-token)))
                            elapsed'  (+ elapsed (or time 0))
                            repeated? (and cursor
                                           (or (= cursor cursor-token)
                                               (contains? seen-cursors cursor)))]
                        (cond
                          error
                          (publish-count! resource-type job-id context
                                          {:status "error"
                                           :count  nil
                                           :time   (explorer/human-duration elapsed')}
                                          true)

                          repeated?
                          (if (zero? count)
                            (publish-count! resource-type job-id context
                                            {:status "done"
                                             :count  (+ total count)
                                             :time   (explorer/human-duration elapsed')}
                                            true)
                            (publish-count! resource-type job-id context
                                            {:status "error"
                                             :count  nil
                                             :time   (explorer/human-duration elapsed')}
                                            true))

                          cursor
                          (do
                            (publish-count! resource-type job-id context
                                            {:status "loading"
                                             :count  (+ total count)
                                             :time   (explorer/human-duration elapsed')}
                                            false)
                            (js/setTimeout
                             #(step cursor
                                    (+ total count)
                                    (min 5000 (* 2 limit))
                                    elapsed'
                                    (conj seen-cursors cursor))
                             0))

                          :else
                          (publish-count! resource-type job-id context
                                          {:status "done"
                                           :count  (+ total count)
                                           :time   (explorer/human-duration elapsed')}
                                          true)))))]
            (js/setTimeout #(step nil 0 250 0 #{}) 0)))))))

(defn start-count-jobs!
  []
  (when (count-jobs-enabled?)
    (doseq [resource-type (resource-types)]
      (let [{:keys [status job-id]} (get-in @!app [:counts resource-type])]
        (when (and (nil? job-id)
                   (not (#{"done" "error" "unavailable"} status)))
          (launch-count-job! resource-type))))))

(def child-section-batch-size 100)
(def child-section-count-batch-size 500)

(defn- child-section-context
  [app section-key parent resource-type]
  {:db-rev        (:db-rev app)
   :subject-id    (explorer/current-subject-id app)
   :permission    (explorer/current-permission app)
   :parent-type   (:type parent)
   :parent-id     (:id parent)
   :resource-type resource-type
   :cursor-token  (explorer/current-child-group-cursor app section-key)})

(defn- child-section-context-keys
  []
  [:db-rev :subject-id :permission :parent-type :parent-id :resource-type :cursor-token])

(defn- same-child-job-context?
  [app section-key job-id context]
  (let [entry (get-in app [:child-sections section-key])]
    (and (= job-id (:job-id entry))
         (= context (select-keys entry (child-section-context-keys))))))

(defn- child-section-entry-current?
  [app section-key context]
  (let [entry (get-in app [:child-sections section-key])]
    (and entry
         (= context (select-keys entry (child-section-context-keys)))
         (#{"loading" "ready" "unavailable"} (:status entry)))))

(defn- publish-child-section!
  [section-key job-id context snapshot final?]
  (let [updated? (atom false)]
    (swap! !app
           (fn [app]
             (let [entry (get-in app [:child-sections section-key])]
               (if (same-child-job-context? app section-key job-id context)
                 (do
                   (reset! updated? true)
                   (assoc-in app [:child-sections section-key]
                             (merge entry
                                    context
                                    snapshot
                                    {:job-id (when-not final? job-id)})))
                 app))))
    @updated?))

(defn- section-parent+type
  [section-key]
  (when-let [[parent-key resource-type] (str/split (str section-key) #">" 2)]
    (when-let [parent (explorer/parse-resource-key parent-key)]
      {:parent        parent
       :resource-type (keyword resource-type)})))

(defn- child-section-page-offset
  [app section-key]
  (* explorer/resource-page-size
     (count (explorer/child-group-cursors app section-key))))

(defn- start-expanded-child-sections-for-resources!
  [resources]
  (when-let [acl (client)]
    (let [app                   @!app
          expanded-resource-keys (get-in app [:ui :expanded-resource-keys] #{})
          expanded-section-keys  (get-in app [:ui :expanded-section-keys] #{})]
      (doseq [resource resources
              :when (contains? expanded-resource-keys (explorer/resource-key resource))
              resource-type (explorer/child-resource-types acl (:type resource))
              :let [section-key (explorer/child-group-key resource resource-type)]
              :when (contains? expanded-section-keys section-key)]
        (ensure-section-key-loaded! section-key)))))

(defn- publish-ready-child-section!
  [section-key job-id context started-at page-items total total-status next-cursor final?]
  (let [page-items' (explorer/hydrate-resources (db) page-items)
        offset      (child-section-page-offset @!app section-key)
        item-count  (count page-items')
        snapshot    {:status      "ready"
                     :items       page-items'
                     :total       total
                     :total-status total-status
                     :page-start  (if (pos? item-count) (inc offset) 0)
                     :page-end    (if (pos? item-count) (+ offset item-count) 0)
                     :next-cursor next-cursor
                     :time        (- (explorer/now-nanos) started-at)
                     :error       nil}]
    (when (publish-child-section! section-key job-id context snapshot final?)
      (start-expanded-child-sections-for-resources! page-items'))))

(defn- finalize-child-section-total!
  [section-key job-id context total total-status]
  (publish-child-section! section-key job-id context
                          {:total        total
                           :total-status total-status}
                          true))

(defn- read-child-relationships
  [acl parent resource-type relation-name cursor-token limit]
  (vec (eacl/read-relationships acl
                                {:subject/type      (:type parent)
                                 :subject/id        (:id parent)
                                 :resource/type     resource-type
                                 :resource/relation relation-name
                                 :cursor            cursor-token
                                 :limit             limit})))

(defn- resource-authorized?
  [acl subject permission permission-implied? resource]
  (or permission-implied?
      (eacl/can? acl subject permission resource)))

(defn- collect-page-items
  [acl subject permission permission-implied? existing-items relationships]
  (loop [remaining relationships
         page-items existing-items]
    (if-let [{:keys [resource]} (first remaining)]
      (if (resource-authorized? acl subject permission permission-implied? resource)
        (if (< (count page-items) explorer/resource-page-size)
          (recur (rest remaining) (conj page-items resource))
          {:page-items page-items
           :has-next?  true})
        (recur (rest remaining) page-items))
      {:page-items page-items
       :has-next?  false})))

(defn- launch-single-relation-total-job!
  [section-key context parent resource-type relation-name job-id permission-implied?]
  (let [acl        (client)
        subject    (seed/->user (:subject-id context))
        permission (:permission context)]
    (letfn [(step [cursor-token total]
              (when (same-child-job-context? @!app section-key job-id context)
                (try
                  (let [relationships  (read-child-relationships acl
                                                                 parent
                                                                 resource-type
                                                                 relation-name
                                                                 cursor-token
                                                                 child-section-count-batch-size)
                        more-batches? (= child-section-count-batch-size (count relationships))
                        next-cursor   (some-> relationships last :resource :id)
                        total'        (+ total
                                         (if permission-implied?
                                           (count relationships)
                                           (reduce (fn [acc {:keys [resource]}]
                                                     (if (eacl/can? acl subject permission resource)
                                                       (inc acc)
                                                       acc))
                                                   0
                                                   relationships)))]
                    (if (and more-batches? next-cursor)
                      (js/setTimeout #(step next-cursor total') 0)
                      (finalize-child-section-total! section-key job-id context total' "ready")))
                  (catch :default _
                    (finalize-child-section-total! section-key job-id context nil "error")))))]
      (js/setTimeout #(step nil 0) 0))))

(defn- launch-single-relation-child-section-job!
  [section-key context parent resource-type relation-def job-id]
  (let [acl        (client)
        subject    (seed/->user (:subject-id context))
        permission (:permission context)
        started-at (explorer/now-nanos)
        offset     (child-section-page-offset @!app section-key)
        relation-name (:eacl.relation/relation-name relation-def)
        permission-implied? (explorer/child-permission-implied-by-parent?
                             acl
                             (:type parent)
                             resource-type
                             relation-name
                             permission)]
    (letfn [(finish-page! [page-items has-next?]
              (if has-next?
                (do
                  (publish-ready-child-section! section-key
                                                job-id
                                                context
                                                started-at
                                                page-items
                                                nil
                                                "loading"
                                                (:id (peek page-items))
                                                false)
                  (launch-single-relation-total-job! section-key
                                                     context
                                                     parent
                                                     resource-type
                                                     relation-name
                                                     job-id
                                                     permission-implied?))
                (publish-ready-child-section! section-key
                                              job-id
                                              context
                                              started-at
                                              page-items
                                              (+ offset (count page-items))
                                              "ready"
                                              nil
                                              true)))
            (step [cursor-token page-items]
              (when (same-child-job-context? @!app section-key job-id context)
                (try
                  (let [relationships    (read-child-relationships acl
                                                                   parent
                                                                   resource-type
                                                                   relation-name
                                                                   cursor-token
                                                                   child-section-batch-size)
                        more-batches?    (= child-section-batch-size (count relationships))
                        next-batch-cursor (some-> relationships last :resource :id)
                        {:keys [page-items has-next?]}
                        (collect-page-items acl
                                            subject
                                            permission
                                            permission-implied?
                                            page-items
                                            relationships)]
                    (cond
                      has-next?
                      (finish-page! page-items true)

                      (and more-batches? next-batch-cursor)
                      (js/setTimeout #(step next-batch-cursor page-items) 0)

                      :else
                      (finish-page! page-items false)))
                  (catch :default ex
                    (publish-child-section! section-key job-id context
                                            {:status "error"
                                             :items []
                                             :total nil
                                             :total-status "error"
                                             :page-start 0
                                             :page-end 0
                                             :next-cursor nil
                                             :time (- (explorer/now-nanos) started-at)
                                             :error (ex-message ex)}
                                            true)))))]
      (js/setTimeout #(step (:cursor-token context) []) 0))))

(defn- launch-fallback-child-section-job!
  [section-key context parent resource-type relation-defs job-id]
  (let [acl        (client)
        subject    (seed/->user (:subject-id context))
        permission (:permission context)
        started-at (explorer/now-nanos)]
    (letfn [(step [remaining-defs cursor-token seen authorized]
              (when (same-child-job-context? @!app section-key job-id context)
                (if-let [relation-def (first remaining-defs)]
                  (try
                    (let [relationships (vec (eacl/read-relationships acl
                                                                       {:subject/type      (:type parent)
                                                                        :subject/id        (:id parent)
                                                                        :resource/type     resource-type
                                                                        :resource/relation (:eacl.relation/relation-name relation-def)
                                                                        :cursor            cursor-token
                                                                        :limit             child-section-batch-size}))
                          resources      (map :resource relationships)
                          [seen' authorized']
                          (reduce (fn [[seen-resources authorized-resources] resource]
                                    (let [key' (explorer/resource-key resource)]
                                      (if (contains? seen-resources key')
                                        [seen-resources authorized-resources]
                                        (if (eacl/can? acl subject permission resource)
                                          [(conj seen-resources key')
                                           (conj authorized-resources resource)]
                                          [(conj seen-resources key')
                                           authorized-resources]))))
                                  [seen authorized]
                                  resources)
                          last-resource-id (some-> relationships last :resource :id)
                          more?            (= child-section-batch-size (count relationships))]
                      (js/setTimeout
                       #(step (if more? remaining-defs (next remaining-defs))
                              (when more? last-resource-id)
                              seen'
                              authorized')
                       0))
                    (catch :default ex
                      (publish-child-section! section-key job-id context
                                              {:status "error"
                                               :items []
                                               :total nil
                                               :total-status "error"
                                               :page-start 0
                                               :page-end 0
                                               :next-cursor nil
                                               :time (- (explorer/now-nanos) started-at)
                                               :error (ex-message ex)}
                                              true)))
                  (let [resources (explorer/hydrate-and-sort-resources (db) authorized)
                        {:keys [items total page-start page-end next-cursor]}
                        (explorer/paginate-resources resources (:cursor-token context))
                        ok? (publish-child-section! section-key job-id context
                                                    {:status     "ready"
                                                     :items      items
                                                     :total      total
                                                     :total-status "ready"
                                                     :page-start page-start
                                                     :page-end   page-end
                                                     :next-cursor next-cursor
                                                     :time       (- (explorer/now-nanos) started-at)
                                                     :error      nil}
                                                    true)]
                    (when ok?
                      (start-expanded-child-sections-for-resources! items))))))]
      (js/setTimeout #(step relation-defs nil #{} []) 0))))

(defn- launch-child-section-job!
  [parent resource-type]
  (when-let [acl (client)]
    (let [app         @!app
          section-key (explorer/child-group-key parent resource-type)
          context     (child-section-context app section-key parent resource-type)
          permission  (:permission context)]
      (if (child-section-entry-current? app section-key context)
        (when (= "ready" (get-in app [:child-sections section-key :status]))
          (start-expanded-child-sections-for-resources!
           (get-in app [:child-sections section-key :items] [])))
        (if-not (explorer/permission-available? (db) acl resource-type permission)
          (swap! !app assoc-in [:child-sections section-key]
                 (merge context
                        {:status      "unavailable"
                         :job-id      nil
                         :items       []
                         :total       0
                         :total-status "ready"
                         :page-start  0
                         :page-end    0
                         :next-cursor nil
                         :time        nil
                         :error       nil}))
          (let [relation-defs (explorer/child-relation-defs acl (:type parent) resource-type)]
            (if-not (seq relation-defs)
              (swap! !app assoc-in [:child-sections section-key]
                     (merge context
                            {:status      "ready"
                             :job-id      nil
                             :items       []
                             :total       0
                             :total-status "ready"
                             :page-start  0
                             :page-end    0
                             :next-cursor nil
                             :time        0
                             :error       nil}))
              (let [job-id (str (random-uuid))]
                (swap! !app assoc-in [:child-sections section-key]
                       (merge context
                              {:status      "loading"
                               :job-id      job-id
                               :items       []
                               :total       nil
                               :total-status "loading"
                               :page-start  0
                               :page-end    0
                               :next-cursor nil
                               :time        nil
                               :error       nil}))
                (if (= 1 (count relation-defs))
                  (launch-single-relation-child-section-job! section-key context parent resource-type (first relation-defs) job-id)
                  (launch-fallback-child-section-job! section-key context parent resource-type relation-defs job-id))))))))))

(defn- ensure-section-key-loaded!
  [section-key]
  (when-let [{:keys [parent resource-type]} (section-parent+type section-key)]
    (launch-child-section-job! parent resource-type)))

(defn restart-expanded-child-section-jobs!
  []
  (doseq [section-key (get-in @!app [:ui :expanded-section-keys] #{})]
    (ensure-section-key-loaded! section-key)))

(defn- update-ui!
  [f]
  (let [before-permission (explorer/current-permission @!app)]
    (swap! !app
           (fn [app]
             (-> app
                 (update :ui f)
                 normalize-ui)))
    (persist-ui! @!app)
    {:permission-changed? (not= before-permission
                                (explorer/current-permission @!app))}))

(defn select-subject!
  [subject-id]
  (update-ui! #(-> %
                   reset-navigation
                   (assoc :subject-id subject-id)))
  (invalidate-child-sections!)
  (invalidate-counts!)
  (start-count-jobs!)
  (restart-expanded-child-section-jobs!))

(defn select-permission!
  [permission]
  (update-ui! #(-> %
                   reset-navigation
                   (assoc :permission (explorer/normalize-permission-name permission))))
  (invalidate-child-sections!)
  (invalidate-counts!)
  (start-count-jobs!)
  (restart-expanded-child-section-jobs!))

(defn select-resource!
  [resource]
  (let [{:keys [permission-changed?]}
        (update-ui! #(assoc % :selected-resource
                            (some-> resource
                                    (update :type explorer/normalize-resource-type))))]
    (when permission-changed?
      (invalidate-child-sections!)
      (invalidate-counts!)
      (start-count-jobs!)
      (restart-expanded-child-section-jobs!))))

(defn set-user-page!
  [page]
  (update-ui! #(assoc % :user-page (max 0 page))))

(defn set-seed-size!
  [value]
  (update-ui! #(assoc % :seed-size-input value)))

(defn set-schema-draft!
  [value]
  (update-ui! #(assoc % :schema-draft value)))

(defn toggle-group!
  [resource-type]
  (update-ui! #(update % :group-expanded
                       (fn [expanded]
                         (if (contains? expanded resource-type)
                           (disj expanded resource-type)
                           (conj expanded resource-type))))))

(defn first-group-page!
  [resource-type]
  (update-ui! #(assoc-in % [:group-prev resource-type] [])))

(defn prev-group-page!
  [resource-type]
  (update-ui! #(update-in % [:group-prev resource-type]
                          (fn [stack]
                            (vec (butlast (vec stack)))))))

(defn next-group-page!
  [resource-type cursor-token]
  (when cursor-token
    (update-ui! #(update-in % [:group-prev resource-type]
                            (fnil conj []) cursor-token))))

(defn toggle-expanded-resource!
  [resource]
  (let [key'       (explorer/resource-key resource)
        expanding? (not (contains? (get-in @!app [:ui :expanded-resource-keys] #{}) key'))]
    (update-ui! #(update % :expanded-resource-keys
                         (fn [expanded]
                           (if (contains? expanded key')
                             (disj expanded key')
                             (conj expanded key')))))
    (when expanding?
      (start-expanded-child-sections-for-resources! [resource]))))

(defn toggle-expanded-section!
  [section-key]
  (let [expanding? (not (contains? (get-in @!app [:ui :expanded-section-keys] #{}) section-key))]
    (update-ui! #(update % :expanded-section-keys
                         (fn [expanded]
                           (if (contains? expanded section-key)
                             (disj expanded section-key)
                             (conj expanded section-key)))))
    (when expanding?
      (ensure-section-key-loaded! section-key))))

(defn nested-first-page!
  [section-key]
  (update-ui! #(assoc-in % [:nested-prev section-key] []))
  (ensure-section-key-loaded! section-key))

(defn nested-prev-page!
  [section-key]
  (update-ui! #(update-in % [:nested-prev section-key]
                          (fn [stack]
                            (vec (butlast (vec stack))))))
  (ensure-section-key-loaded! section-key))

(defn nested-next-page!
  [section-key cursor-token]
  (when cursor-token
    (update-ui! #(update-in % [:nested-prev section-key]
                            (fnil conj []) cursor-token))
    (ensure-section-key-loaded! section-key)))

(defn toggle-schema!
  []
  (update-ui! #(update % :schema-expanded? not)))

(defn- set-bootstrap-state!
  [bootstrap]
  (swap! !app assoc :bootstrap (merge default-bootstrap bootstrap)))

(defn- parse-seed-size
  [value]
  (let [n (js/parseInt (str value) 10)]
    (when (and (js/Number.isFinite n)
               (pos? n))
      n)))

(defn write-schema!
  []
  (when-let [acl (client)]
    (let [draft     (or (get-in @!app [:ui :schema-draft]) "")
          committed (explorer/schema-source (db))]
      (when (not= draft committed)
        (swap! !app update :bootstrap merge
               {:status       :writing-schema
                :schema-error nil})
        (try
          (eacl/write-schema! acl draft)
          (swap! !app
                 (fn [app]
                   (-> app
                       normalize-ui
                       (update :bootstrap merge
                               {:status       :ready
                                :schema-error nil}))))
          (restart-expanded-child-section-jobs!)
          (catch :default ex
            (swap! !app update :bootstrap merge
                   {:status       :ready
                    :schema-error (ex-message ex)})))))))

(defn seed-db!
  []
  (when-let [{:keys [conn client]} @!runtime]
    (if-let [servers-total (parse-seed-size (get-in @!app [:ui :seed-size-input]))]
      (let [{:keys [batches]} (seed/seed-more-plan (d/db conn) servers-total)
            total-batches     (count batches)]
        (set-bootstrap-state!
         {:status            :seeding
          :totals            (seed/current-totals (d/db conn))
          :servers-target    servers-total
          :servers-completed 0
          :batch             0
          :total-batches     total-batches
          :label             "Preparing seed batches"
          :seed-error        nil
          :schema-error      (get-in @!app [:bootstrap :schema-error])})
        (invalidate-child-sections!)
        (invalidate-counts!)
        (letfn [(finish! [status extra]
                  (when (and (= status :ready)
                             (nil? (:seed-error extra)))
                    (update-ui! #(assoc % :user-page 0)))
                  (set-bootstrap-state!
                   (merge {:status            status
                           :totals            (seed/current-totals (d/db conn))
                           :servers-target    0
                           :servers-completed 0
                           :batch             0
                           :total-batches     0
                           :label             nil
                           :schema-error      (get-in @!app [:bootstrap :schema-error])}
                          extra))
                  (invalidate-child-sections!)
                  (invalidate-counts!)
                  (start-count-jobs!)
                  (when (= status :ready)
                    (restart-expanded-child-section-jobs!)))
                (step [remaining batch-n servers-completed]
                  (if-let [batch (first remaining)]
                    (let [servers-completed' (+ servers-completed (long (or (:servers-added batch) 0)))]
                      (try
                        (seed/execute-batch! conn client batch)
                        (set-bootstrap-state!
                         {:status            :seeding
                          :totals            (seed/current-totals (d/db conn))
                          :servers-target    servers-total
                          :servers-completed servers-completed'
                          :batch             (inc batch-n)
                          :total-batches     total-batches
                          :label             (:label batch)
                          :seed-error        nil
                          :schema-error      (get-in @!app [:bootstrap :schema-error])})
                        (js/setTimeout #(step (next remaining) (inc batch-n) servers-completed') 0)
                        (catch :default ex
                          (finish! :ready
                                   {:seed-error (ex-message ex)}))))
                    (finish! :ready
                             {:seed-error nil})))]
          (js/setTimeout #(step batches 0 0) 0)))
      (swap! !app update :bootstrap merge
             {:seed-error "Seed size must be a positive integer."}))))

(defn initialize-runtime!
  []
  (when-not @!runtime
    (let [runtime seed/shared-runtime
          conn    (:conn runtime)
          client  (:client runtime)]
      (reset! !runtime runtime)
      (when-not (seed/seed-state (d/db conn))
        (seed/install-foundation! conn client))
      (let [db' (d/db conn)]
        (reset! !app
                (assoc (initial-app-state) :ui
                       (assoc (load-ui-state)
                              :schema-draft (explorer/schema-source db')))))
      (register-db-listener!)
      (swap! !app
             (fn [app]
               (-> app
                   normalize-ui
                   (assoc :counts (explorer/count-state (resource-types)))
                   (assoc :bootstrap
                          (merge default-bootstrap
                                 {:status :ready
                                  :totals (seed/current-totals (d/db conn))})))))
      (persist-ui! @!app)
      (start-count-jobs!))))
