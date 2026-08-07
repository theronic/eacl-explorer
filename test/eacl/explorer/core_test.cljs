(ns eacl.explorer.core-test
  (:require [cljs.test :refer-macros [deftest is]]
            [eacl.explorer.core :as core]
            [eacl.explorer.state :as state]))

(deftest identifier-label-is-safe-for-ui-display
  (is (= "server" (core/identifier-label :server)))
  (is (= "server" (core/identifier-label 'server)))
  (is (= "server" (core/identifier-label "server")))
  (is (nil? (core/identifier-label nil))))

(deftest permission-label-handles-non-keyword-values
  (is (= ":view" (core/permission-label :view)))
  (is (= ":view" (core/permission-label 'view)))
  (is (= ":view" (core/permission-label "view")))
  (is (= "No permission" (core/permission-label nil))))

(deftest cache-badges-have-exact-text-and-state-classes
  (is (= [:span.cache-badge
          {:class "cache-badge--hit"
           :data-cache-status "hit"}
          "HIT"]
         (core/cache-badge :hit)))
  (is (= [:span.cache-badge
          {:class "cache-badge--miss"
           :data-cache-status "miss"}
          "MISS"]
         (core/cache-badge :miss)))
  (is (= [:span.cache-badge
          {:class "cache-badge--disabled"
           :data-cache-status "disabled"}
          "CACHE DISABLED"]
         (core/cache-badge :disabled))))

(declare hiccup-nodes)

(deftest schema-preset-tabs-replace-only-the-unsaved-draft
  (let [drafts (atom [])
        presets [{:id :non-recursive
                  :label "Non-recursive"
                  :schema "non-recursive schema"}
                 {:id :recursive
                  :label "Recursive"
                  :schema "recursive schema"}]
        tabs
        (with-redefs [state/set-schema-draft!
                      #(swap! drafts conj %)]
          (core/schema-preset-tabs "non-recursive schema" presets))
        buttons (filter
                 #(= :button.schema-preset-tab (first %))
                 (hiccup-nodes tabs))
        recursive-button (second buttons)]
    (is (= 2 (count buttons)))
    (is (true? (:aria-selected (second (first buttons)))))
    (is (false? (:aria-selected (second recursive-button))))
    (with-redefs [state/set-schema-draft!
                  #(swap! drafts conj %)]
      ((:on-click (second recursive-button)) nil))
    (is (= ["recursive schema"] @drafts))))

(defn- hiccup-nodes
  [tree]
  (filter vector? (tree-seq vector? seq tree)))

(defn- hiccup-node
  [tree tag]
  (some #(when (= tag (first %)) %) (hiccup-nodes tree)))

(deftest cache-timing-places-provenance-immediately-before-duration
  (let [timing (core/cache-timing :hit "84.50ms")]
    (is (= :span.cache-badge
           (first (nth timing 2))))
    (is (= "HIT" (last (nth timing 2))))
    (is (= [:span.cache-timing__duration "84.50ms"]
           (nth timing 3)))))

(deftest schema-heading-does-not-render-cache-provenance
  (let [heading
        (core/schema-panel-heading
         {:expanded? false
          :cache-status :miss
          :resource-count 6
          :relation-count 11
          :permission-count 8})]
    (is (nil? (hiccup-node heading :span.cache-badge)))
    (is (= "Schema (6 resources, 11 relations, 8 permissions)"
           (last (hiccup-node heading :span.group-card__title))))))

(deftest cache-panel-renders-control-state-and-disclosure
  (let [collapsed (core/cache-panel
                   {:enabled? false
                    :expanded? false
                    :metrics {:edn "{:entries 0}\n"}})
        expanded (core/cache-panel
                  {:enabled? true
                   :expanded? true
                   :metrics {:edn "{:entries 2}\n"}})
        collapsed-toggle (hiccup-node collapsed :input.cache-switch__input)
        expanded-toggle (hiccup-node expanded :input.cache-switch__input)]
    (is (= "panel-card--collapsed" (:class (second collapsed))))
    (is (false? (:checked (second collapsed-toggle))))
    (is (true? (:checked (second expanded-toggle))))
    (is (= "switch" (:role (second expanded-toggle))))
    (is (= "Cache Enabled"
           (:aria-label (second expanded-toggle))))
    (is (true? (:aria-checked (second expanded-toggle))))
    (is (= "Cache Enabled:"
           (last (hiccup-node expanded :span.cache-toggle__label))))
    (is (= "On"
           (last (hiccup-node expanded :span.cache-toggle__state))))
    (is (= "Off"
           (last (hiccup-node collapsed :span.cache-toggle__state))))
    (is (nil? (hiccup-node collapsed :pre.cache-metrics__code)))
    (is (= "{:entries 2}\n"
           (last (hiccup-node expanded :code))))))

(deftest cache-panel-wires-eviction-and-renders-metrics-errors
  (let [evictions (atom 0)
        panel (with-redefs [state/evict-cache!
                            (fn [] (swap! evictions inc))]
                (let [rendered
                      (core/cache-panel
                       {:enabled? true
                        :expanded? true
                        :metrics {:error "metrics unavailable"}})
                      button (some
                              #(when (and (= :button.pagination-button.cache-evict
                                             (first %))
                                          (= "Evict Cache" (last %)))
                                 %)
                              (hiccup-nodes rendered))]
                  ((:on-click (second button)) nil)
                  rendered))]
    (is (= 1 @evictions))
    (is (= "metrics unavailable"
           (last (hiccup-node panel :div.error-block))))))
