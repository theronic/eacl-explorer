(ns eacl.explorer.core
  (:require [rum.core :as rum]
            [eacl.explorer.explorer :as explorer]
            [eacl.explorer.state :as app-state]))

(defonce root-el (atom nil))
(defonce count-formatter (js/Intl.NumberFormat. "en-US"))

(defn- format-count
  [n]
  (.format count-formatter (long n)))

(defn- pagination-summary
  [page-start page-end total time]
  (str (if (pos? page-end)
         (str page-start "–" page-end)
         "0")
       " of "
       (cond
         (number? total) (format-count total)
         (string? total) total
         :else           "...")
       (when time
         (str " (" (explorer/human-duration time) ")"))))

(defn- type-class
  [resource-type]
  (str "type-" (name resource-type)))

(defn- type-badge
  [resource-type]
  [:span.type-badge {:class (type-class resource-type)}])

(defn- active?
  [expected actual]
  (= expected actual))

(defn- permission-label
  [permission]
  (if permission
    (str ":" (name permission))
    "No permission"))

(defn- selected-resource?
  [selected resource]
  (and selected
       (= (:type selected) (:type resource))
       (= (:id selected) (:id resource))))

(defn- resource-caption
  [resource]
  (let [display-name (:display-name resource)
        resource-id  (:id resource)]
    [:span.resource-caption
     [:span.resource-caption__name display-name]
     (when (and display-name (not= display-name resource-id))
       [:span.resource-caption__id resource-id])]))

(defn- server-stat-node
  [{:keys [total status progress target]}]
  [:div.stat-pill
   [:span.stat-pill__label
    (case status
      :ready   "seeded"
      :seeding "seeding"
      :writing-schema "schema"
      "loading")]
   [:strong
    (case status
      :ready   (str (format-count total) " servers")
      :seeding (str (format-count progress) " / " (format-count target) " servers")
      :writing-schema (str (format-count total) " servers")
      "Loading explorer")]])

(defn- loading-panel
  [title message]
  [:div.panel-card
   [:div.panel-heading
    [:p.panel-kicker title]]
   [:div.empty-state message]])

(defn- count-text
  [{:keys [count count-status count-time]}]
  (cond
    (= "unavailable" count-status)
    "n/a"

    (number? count)
    (str (format-count count)
         (when (= "loading" count-status) "…")
         (when count-time
           (str " (" count-time ")")))

    (= "error" count-status)
    "error"

    :else
    "..."))

(defn- range-text
  [{:keys [page-start page-end count count-status time]}]
  (let [total (cond
                (= "unavailable" count-status) "n/a"
                (number? count) (str (format-count count) (when (= "loading" count-status) "…"))
                (= "error" count-status) "error"
                :else "...")]
    (pagination-summary page-start page-end total time)))

(defn- section-count-text
  [{:keys [expanded? page-start page-end total time supported? load-status total-status]}]
  (if-not supported?
    "n/a"
    (let [total-text (case total-status
                       "ready" total
                       "error" "error"
                       "...")]
      (case load-status
        "error" "error"
        "ready" (if expanded?
                  (pagination-summary page-start page-end total-text time)
                  (if (= "ready" total-status)
                    (str (format-count total) " total")
                    total-text))
        "..."))))

(declare render-resource-node)

(defn- render-child-group
  [selected-resource group]
  [:div.relationship-group {:id (str "section-" (:section-key group))}
   [:div.group-card__header
    [:button.group-card__toggle
     {:on-click #(app-state/toggle-expanded-section! (:section-key group))}
     [:span.group-card__caret (if (:expanded? group) "▾" "▸")]
     (type-badge (:resource-type group))
     [:span.relationship-group__title (str (name (:resource-type group)) "s")]]
    [:div.group-card__stats
     [:span.relationship-group__count (section-count-text group)]]]
   (when (:expanded? group)
     [:div.group-card__body
      [:div.group-card__meta
       [:span.tree-meta__time (str "Page " (:page-number group))]
       (when-let [error (:error group)]
         [:span.tree-meta__error error])]
      [:div.pagination-row
      [:button.pagination-button
        {:on-click #(app-state/nested-first-page! (:section-key group))
         :disabled (or (not (:supported? group))
                       (= 1 (:page-number group)))}
        "First"]
       [:button.pagination-button
        {:on-click #(app-state/nested-prev-page! (:section-key group))
         :disabled (or (not (:supported? group))
                       (= 1 (:page-number group)))}
        "Prev"]
       [:button.pagination-button
        {:on-click #(app-state/nested-next-page! (:section-key group) (:next-cursor group))
         :disabled (or (not (:supported? group))
                       (nil? (:next-cursor group)))}
        "Next"]]
      (if-let [error (:error group)]
        [:div.error-block error]
        (if-let [notice (:notice group)]
          [:div.empty-state notice]
          (if (seq (:items group))
          [:div.relationship-group__items
           (for [child (:items group)]
             [:div {:key (explorer/resource-key child)}
              (render-resource-node selected-resource child)])]
          [:div.empty-state "No resources on this page."])))])])

(defn- render-resource-children
  [selected-resource node]
  (when-let [{:keys [groups error]} (:children node)]
    [:div.tree-children
     (when error
       [:div.tree-meta
        [:span.tree-meta__error error]])
     (for [group groups]
       [:div {:key (:section-key group)}
        (render-child-group selected-resource group)])]))

(defn- render-resource-node
  [selected-resource resource]
  (let [expandable?   (:expandable? resource)
        has-children? (or expandable? (contains? resource :children))
        expanded?     (:expanded? resource)]
    [:div.resource-node {:id (str "resource-node-" (explorer/resource-key resource))}
     [:div.resource-node__row
      [:button.expand-toggle
       {:disabled (not has-children?)
        :on-click #(when has-children?
                     (app-state/toggle-expanded-resource! resource))}
       (cond
         (not has-children?) "·"
         expanded? "▾"
         :else "▸")]
      [:button.resource-button
       {:class    (when (selected-resource? selected-resource resource) "resource-button--active")
        :on-click #(app-state/select-resource! (select-keys resource [:type :id]))}
       (type-badge (:type resource))
       (resource-caption resource)]]
     (when expanded?
       (render-resource-children selected-resource resource))]))

(defn- render-group
  [selected-resource group]
  [:div.group-card {:id (str "resource-group-" (name (:resource-type group)))}
   [:div.group-card__header
    [:button.group-card__toggle
     {:on-click #(app-state/toggle-group! (:resource-type group))}
     [:span.group-card__caret (if (:expanded? group) "▾" "▸")]
     (type-badge (:resource-type group))
     [:span.group-card__title (str (name (:resource-type group)) "s")]]
    [:div.group-card__stats
     [:span.group-card__count (count-text group)]
     (when (:expanded? group)
       [:span.group-card__range (range-text group)])]]
   (when (:expanded? group)
     [:div.group-card__body
      [:div.group-card__meta
       [:span.tree-meta__time (str "Page " (:page-number group))]
       (when-let [error (:error group)]
         [:span.tree-meta__error error])]
      [:div.pagination-row
      [:button.pagination-button
        {:on-click #(app-state/first-group-page! (:resource-type group))
         :disabled (or (not (:supported? group))
                       (= 1 (:page-number group)))}
        "First"]
       [:button.pagination-button
        {:on-click #(app-state/prev-group-page! (:resource-type group))
         :disabled (or (not (:supported? group))
                       (= 1 (:page-number group)))}
        "Prev"]
       [:button.pagination-button
        {:on-click #(app-state/next-group-page! (:resource-type group) (:next-cursor group))
         :disabled (or (not (:supported? group))
                       (nil? (:next-cursor group)))}
        "Next"]]
      (if-let [error (:error group)]
        [:div.error-block error]
        (if-let [notice (:notice group)]
          [:div.empty-state notice]
          (if (seq (:items group))
          [:div.list-stack
           (for [resource (:items group)]
             [:div {:key (explorer/resource-key resource)}
              (render-resource-node selected-resource resource)])]
          [:div.empty-state "No resources on this page."])))])])

(defn- subject-panel
  [{:keys [current-subject permission permissions quick-subjects user-page]}]
  [:div.panel-card
   [:div.panel-heading
    [:p.panel-kicker "Subjects"]]
   [:div.active-summary.active-summary--subject
    [:span.active-summary__label "Active subject"]
    [:span.active-summary__value current-subject]]
   [:div.panel-section
    [:p.panel-label "Permission"]
    (if (seq permissions)
      [:div.chip-row
       (for [perm permissions]
         [:button.chip
          {:key      (name perm)
           :class    (when (active? perm permission) "chip--active")
           :on-click #(app-state/select-permission! perm)}
          (name perm)])]
      [:div.empty-state "No permissions defined in the current schema."])]
   [:div.panel-section
    [:p.panel-label "Quick Subjects"]
    [:div.button-stack.button-stack--inline
     (for [{:keys [id label]} quick-subjects]
       [:button.subject-button
        {:key      id
         :class    (when (active? id current-subject) "subject-button--active")
         :on-click #(app-state/select-subject! id)}
        label])]]
   [:div.panel-section
    [:div.section-header
     [:div
      [:p.panel-label "Known Users"]
      [:p.section-meta
       (pagination-summary (:page-start user-page)
         (:page-end user-page)
         (:total user-page)
         (:time user-page))]]]
    [:div.pagination-row
     [:button.pagination-button
      {:disabled (not (:has-prev? user-page))
       :on-click #(app-state/set-user-page! (dec (:page user-page)))}
      "Prev"]
     [:button.pagination-button
      {:disabled (not (:has-next? user-page))
       :on-click #(app-state/set-user-page! (inc (:page user-page)))}
      "Next"]]
    (if (seq (:items user-page))
      [:div.list-stack
       (for [user-id (:items user-page)]
         [:button.list-item
          {:key      user-id
           :class    (when (active? user-id current-subject) "list-item--active")
           :on-click #(app-state/select-subject! user-id)}
          (type-badge :user)
          [:span.list-item__text user-id]])]
      [:div.empty-state "No known users yet."])]])

(defn- resource-panel
  [{:keys [groups selected-resource subject-id permission]}]
  [:div.panel-card
   [:div.panel-heading
    [:p.panel-kicker "Resources"]]
   [:div.panel-summary
    [:span.panel-summary__value subject-id]
    [:span.panel-summary__separator "·"]
    [:span.panel-summary__value (permission-label permission)]]
   (for [group groups]
     [:div {:key (name (:resource-type group))}
      (render-group selected-resource group)])])

(defn- detail-panel
  [{:keys [resource current-subject permissions error]}]
  (if-not resource
    (loading-panel "Detail" "Select a resource to inspect.")
    [:div.panel-card
     [:div.panel-heading
      [:p.panel-kicker "Detail"]]
     [:div.panel-section
      [:div.detail-header
       (type-badge (:type resource))
       [:div
        [:p.detail-header__title (name (:type resource))]
        [:p.detail-header__subtitle (or (:display-name resource) (:id resource))]
        (when (and (:display-name resource)
                   (not= (:display-name resource) (:id resource)))
          [:p.detail-header__id (:id resource)])]]]
     (if error
       [:div.error-block error]
       (if (seq permissions)
         (for [{:keys [permission subjects time error]} permissions]
           [:div.panel-section {:key (name permission)}
            [:div.section-header
             [:div
              [:p.panel-label (str ":" (name permission))]
              [:p.section-meta (str (count subjects) " subjects")]]
             (when time
               [:div.pagination-hint (explorer/human-duration time)])]
            (if error
              [:div.error-block error]
              (if (seq subjects)
                [:div.list-stack
                 (for [{:keys [type id]} subjects]
                   [:button.list-item
                    {:key      id
                     :class    (when (active? id current-subject) "list-item--active")
                     :on-click #(app-state/select-subject! id)}
                    (type-badge type)
                    [:span.list-item__text id]])]
                [:div.empty-state "No subjects found for this permission."]))])
         [:div.empty-state "No permissions defined for this resource type."]))]))

(defn- render-schema-graph!
  [panel-data]
  (when (and (:expanded? panel-data)
             (some-> js/document (.getElementById "schema-graph-canvas")))
    (when-let [renderer (some-> js/window .-EaclSchemaGraph .-render)]
    (renderer "schema-graph-canvas"
      (clj->js (select-keys panel-data [:nodes :links]))))))

(rum/defcs schema-panel <
  {:did-mount
   (fn [state]
     (render-schema-graph! (first (:rum/args state)))
     state)
   :did-update
   (fn [state]
     (render-schema-graph! (first (:rum/args state)))
     state)}
  [rum-state panel-data]
  [:div.panel-card.panel-card--graph
   {:class (when-not (:expanded? panel-data) "panel-card--collapsed")}
   [:div.panel-heading.schema-shell__header
   [:button.group-card__toggle
     {:on-click #(app-state/toggle-schema!)}
     [:span.group-card__caret (if (:expanded? panel-data) "▾" "▸")]
     [:span.group-card__title "Schema"]]
    (when-let [status-text (cond
                             (:writing? panel-data) "Writing schema"
                             (:changed? panel-data) "Unsaved changes"
                             :else nil)]
      [:div.group-card__stats
       [:span.section-meta status-text]])]
   (when (:expanded? panel-data)
     [:div.schema-panel
      [:section.schema-panel__pane
       [:div.section-header
        [:div
         [:p.panel-label "Spice Schema"]
         [:p.section-meta "Edit the Spice schema and click Write Schema"]]]
       [:textarea.schema-editor
        {:id        "schema-editor"
         :name      "schema-editor"
         :value     (:draft-text panel-data)
         :on-change #(app-state/set-schema-draft! (.. % -target -value))
         :spellCheck false}]
       [:div.schema-panel__actions
        (when-let [error (:error panel-data)]
          [:div.error-block error])
        [:button.pagination-button
         {:disabled (:write-disabled? panel-data)
          :on-click #(app-state/write-schema!)}
         "Write Schema"]]]
      [:section.schema-panel__pane
       [:div.section-header
        [:div
         [:p.panel-label "Schema Graph"]
         [:p.section-meta "Resources, permissions, and relation paths"]]]
       [:div#schema-graph-canvas.graph-canvas]]])])

(defn- shell-header
  [bootstrap seed-size-input]
  (let [stat   (explorer/server-stat-data {:bootstrap bootstrap})
        status (:status bootstrap :booting)]
    [:header.app-header
     [:div
      [:p.eyebrow "EACL v7 + DataScript + Rum"]
      [:h1.app-title "EACL Explorer"]
      [:p.app-subtitle
       (case status
         :ready   "This EACL demo is backed by a client-side DataScript database, but would typically run server-side, backed by Datomic Pro or Datomic Cloud."
         :seeding "Appending benchmark-style data into the live DataScript explorer."
         :writing-schema "Applying Spice schema changes to the live explorer."
         "Booting the client explorer.")]]
     [:div.app-header__actions
      [:div.stat-host (server-stat-node stat)]
      [:form.seed-controls
       {:on-submit (fn [event]
                     (.preventDefault event)
                     (when (= :ready status)
                       (app-state/seed-db!)))}
       [:input.seed-input
        {:id        "seed-size"
         :name      "seed-size"
         :type      "number"
         :min       1
         :step      1
         :value     seed-size-input
         :on-change #(app-state/set-seed-size! (.. % -target -value))}]
       [:button.graph-toggle
        {:type "submit"
         :disabled (not= :ready status)}
        "Seed DB"]]
      (when-let [seed-error (:seed-error bootstrap)]
        [:div.error-block.app-header__error seed-error])]]))

(defn- loading-grid
  [bootstrap]
  [:main.panel-grid
   [:section.panel-host
    (loading-panel "Subjects"
      (case (:status bootstrap)
        :seeding "Preparing the explorer..."
        "Loading subjects..."))]
   [:section.panel-host
    (loading-panel "Resources"
      (case (:status bootstrap)
        :seeding "Preparing the explorer..."
        "Loading resources..."))]
   [:section.panel-host
    (loading-panel "Detail"
      (case (:status bootstrap)
        :seeding "Explorer detail will be available after boot."
        "Loading detail..."))]])

(defn- subject-view-state
  [subject-id permission user-page db-rev]
  {:ui {:subject-id subject-id
        :permission permission
        :user-page  user-page}
   :db-rev db-rev})

(defn- group-view-state
  [resource-type subject-id permission group-expanded group-cursors
   expanded-resource-keys expanded-section-keys nested-prev count-entry child-sections db-rev]
  {:ui {:subject-id             subject-id
        :permission             permission
        :group-expanded         (if (contains? group-expanded resource-type)
                                  #{resource-type}
                                  #{})
        :group-prev             {resource-type (vec (or group-cursors []))}
        :expanded-resource-keys expanded-resource-keys
        :expanded-section-keys  expanded-section-keys
        :nested-prev            nested-prev}
   :counts {resource-type count-entry}
   :child-sections child-sections
   :db-rev db-rev})

(defn- detail-view-state
  [subject-id permission selected-resource db-rev]
  {:ui {:subject-id        subject-id
        :permission        permission
        :selected-resource selected-resource}
   :db-rev db-rev})

(rum/defcs shell-header-view < rum/reactive
  [rum-state]
  (let [bootstrap    (rum/react (rum/cursor-in app-state/!app [:bootstrap]))
        seed-size-input (rum/react (rum/cursor-in app-state/!app [:ui :seed-size-input]))]
    (shell-header bootstrap seed-size-input)))

(rum/defcs schema-shell-view < rum/reactive
  [rum-state]
  (let [bootstrap    (rum/react (rum/cursor-in app-state/!app [:bootstrap]))
        schema-expanded? (boolean (rum/react (rum/cursor-in app-state/!app [:ui :schema-expanded?])))
        schema-draft (rum/react (rum/cursor-in app-state/!app [:ui :schema-draft]))
        db-rev       (rum/react (rum/cursor-in app-state/!app [:db-rev]))
        ready?       (not= :booting (:status bootstrap))
        db           (app-state/db)
        acl          (app-state/client)]
    (when ready?
      [:section.schema-shell
       (schema-panel
         (assoc (explorer/schema-panel-data db acl {:db-rev db-rev})
           :expanded?      schema-expanded?
           :draft-text     schema-draft
           :changed?       (not= schema-draft (explorer/schema-source db))
           :writing?       (= :writing-schema (:status bootstrap))
           :write-disabled? (or (= :writing-schema (:status bootstrap))
                                (= schema-draft (explorer/schema-source db)))
           :error          (:schema-error bootstrap)))])))

(rum/defcs subject-panel-view < rum/reactive
  [rum-state]
  (let [db            (app-state/db)
        acl           (app-state/client)
        subject-id    (rum/react (rum/cursor-in app-state/!app [:ui :subject-id]))
        permission    (rum/react (rum/cursor-in app-state/!app [:ui :permission]))
        selected-resource (rum/react (rum/cursor-in app-state/!app [:ui :selected-resource]))
        user-page     (rum/react (rum/cursor-in app-state/!app [:ui :user-page]))
        db-rev        (rum/react (rum/cursor-in app-state/!app [:db-rev]))
        view-state    (assoc (subject-view-state subject-id permission user-page db-rev)
                        :ui {:subject-id        subject-id
                             :permission        permission
                             :user-page         user-page
                             :selected-resource selected-resource})
        subject-data  (explorer/paged-known-users db nil acl view-state)]
    (subject-panel {:current-subject subject-id
                    :permission      permission
                    :permissions     (explorer/selectable-permissions db acl view-state)
                    :quick-subjects  explorer/quick-subjects
                    :user-page       subject-data})))

(rum/defcs resource-group-view < rum/reactive
  [rum-state resource-type]
  (let [db                     (app-state/db)
        acl                    (app-state/client)
        subject-id             (rum/react (rum/cursor-in app-state/!app [:ui :subject-id]))
        permission             (rum/react (rum/cursor-in app-state/!app [:ui :permission]))
        group-expanded         (rum/react (rum/cursor-in app-state/!app [:ui :group-expanded]))
        group-cursors          (rum/react (rum/cursor-in app-state/!app [:ui :group-prev resource-type]))
        expanded-resource-keys (rum/react (rum/cursor-in app-state/!app [:ui :expanded-resource-keys]))
        expanded-section-keys  (rum/react (rum/cursor-in app-state/!app [:ui :expanded-section-keys]))
        nested-prev            (rum/react (rum/cursor-in app-state/!app [:ui :nested-prev]))
        count-entry            (rum/react (rum/cursor-in app-state/!app [:counts resource-type]))
        child-sections         (rum/react (rum/cursor-in app-state/!app [:child-sections]))
        selected-resource      (rum/react (rum/cursor-in app-state/!app [:ui :selected-resource]))
        db-rev                 (rum/react (rum/cursor-in app-state/!app [:db-rev]))
        view-state             (group-view-state resource-type
                                 subject-id
                                 permission
                                 group-expanded
                                 group-cursors
                                 expanded-resource-keys
                                 expanded-section-keys
                                 nested-prev
                                 count-entry
                                 child-sections
                                 db-rev)]
    (render-group selected-resource
      (explorer/group-data db acl view-state resource-type))))

(rum/defcs resource-panel-view < rum/reactive
  [rum-state]
  (let [subject-id      (rum/react (rum/cursor-in app-state/!app [:ui :subject-id]))
        permission      (rum/react (rum/cursor-in app-state/!app [:ui :permission]))
        db-rev          (rum/react (rum/cursor-in app-state/!app [:db-rev]))
        resource-types  (explorer/query-resource-types (app-state/db) (app-state/client))]
    [:div.panel-card
     [:div.panel-heading
      [:p.panel-kicker "Resources"]]
     [:div.panel-summary
      [:span.panel-summary__value subject-id]
      [:span.panel-summary__separator "·"]
      [:span.panel-summary__value (if permission
                                    (str ":" (name permission))
                                    "No active permission")]]
     (if (seq resource-types)
       (for [resource-type resource-types]
         [:div {:key (str (name resource-type) "-" db-rev)}
          (resource-group-view resource-type)])
       [:div.empty-state "No queryable resource types in the current schema."])]))

(rum/defcs detail-panel-view < rum/reactive
  [rum-state]
  (let [db                (app-state/db)
        acl               (app-state/client)
        subject-id        (rum/react (rum/cursor-in app-state/!app [:ui :subject-id]))
        permission        (rum/react (rum/cursor-in app-state/!app [:ui :permission]))
        selected-resource (rum/react (rum/cursor-in app-state/!app [:ui :selected-resource]))
        db-rev            (rum/react (rum/cursor-in app-state/!app [:db-rev]))
        detail-data       (assoc (explorer/resource-detail-data db acl
                                   (detail-view-state subject-id permission selected-resource db-rev))
                            :current-subject subject-id)]
    (detail-panel detail-data)))

(rum/defcs app-body-view < rum/reactive
  [rum-state]
  (let [bootstrap (rum/react (rum/cursor-in app-state/!app [:bootstrap]))]
    (if (= :booting (:status bootstrap))
      (loading-grid bootstrap)
      [:main.panel-grid
       [:section.panel-host
        (subject-panel-view)]
       [:section.panel-host
        (resource-panel-view)]
       [:section.panel-host
        (detail-panel-view)]])))

(rum/defc app-root []
  [:div.app-shell
   (shell-header-view)
   (schema-shell-view)
   (app-body-view)])

(defn mount!
  []
  (when-let [element (or @root-el (.getElementById js/document "app"))]
    (reset! root-el element)
    (rum/mount (app-root) element)))

(defn init!
  []
  (app-state/initialize-runtime!)
  (mount!))
