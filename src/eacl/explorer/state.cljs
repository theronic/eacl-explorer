(ns eacl.explorer.state
  (:require [datascript.core :as d]
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
      :permission (or (some-> stored-permission keyword)
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
    (explorer/query-resource-types acl)
    explorer/default-resource-types))

(defn- available-permissions
  []
  (if-let [acl (client)]
    (explorer/available-permissions acl)
    []))

(defn- normalize-ui
  [app]
  (let [permissions (available-permissions)
        current     (explorer/current-permission app)
        normalized  (or (when (some #{current} permissions) current)
                        (first permissions)
                        nil)]
    (if (= current normalized)
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

(declare start-count-jobs!)

(defn on-db-change!
  []
  (swap! !app
    (fn [app]
      (-> app
          normalize-ui
          (update :db-rev (fnil inc 0)))))
  (when (ready?)
    (invalidate-counts!)
    (start-count-jobs!)))

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
      (if-not (explorer/permission-available? acl resource-type permission)
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

(defn- update-ui!
  [f]
  (swap! !app update :ui f)
  (persist-ui! @!app))

(defn select-subject!
  [subject-id]
  (update-ui! #(-> %
                   reset-navigation
                   (assoc :subject-id subject-id)))
  (invalidate-counts!)
  (start-count-jobs!))

(defn select-permission!
  [permission]
  (update-ui! #(-> %
                   reset-navigation
                   (assoc :permission permission)))
  (invalidate-counts!)
  (start-count-jobs!))

(defn select-resource!
  [resource]
  (update-ui! #(assoc % :selected-resource resource)))

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
  (let [key' (explorer/resource-key resource)]
    (update-ui! #(update % :expanded-resource-keys
                   (fn [expanded]
                     (if (contains? expanded key')
                       (disj expanded key')
                       (conj expanded key')))))))

(defn toggle-expanded-section!
  [section-key]
  (update-ui! #(update % :expanded-section-keys
                 (fn [expanded]
                   (if (contains? expanded section-key)
                     (disj expanded section-key)
                     (conj expanded section-key))))))

(defn nested-first-page!
  [section-key]
  (update-ui! #(assoc-in % [:nested-prev section-key] [])))

(defn nested-prev-page!
  [section-key]
  (update-ui! #(update-in % [:nested-prev section-key]
                 (fn [stack]
                   (vec (butlast (vec stack)))))))

(defn nested-next-page!
  [section-key cursor-token]
  (when cursor-token
    (update-ui! #(update-in % [:nested-prev section-key]
                   (fnil conj []) cursor-token))))

(defn toggle-schema!
  []
  (update-ui! #(update % :show-schema? not)))

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
        (invalidate-counts!)
        (letfn [(finish! [status extra]
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
                  (invalidate-counts!)
                  (start-count-jobs!))
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
    (let [runtime (seed/create-runtime)]
      (reset! !runtime runtime)
      (reset! !app (assoc (initial-app-state) :ui (load-ui-state)))
      (register-db-listener!)
      (seed/install-foundation! (:conn runtime) (:client runtime))
      (swap! !app
        (fn [app]
          (-> app
              normalize-ui
              (assoc :counts (explorer/count-state (resource-types)))
              (assoc :bootstrap
                (merge default-bootstrap
                  {:status :ready
                   :totals (seed/current-totals (d/db (:conn runtime)))})))))
      (persist-ui! @!app)
      (start-count-jobs!))))
