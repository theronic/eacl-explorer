(ns eacl.bench.query-benchmark
  "Browser-side Explorer query benchmark.

  This namespace is included in the test build but deliberately excluded from
  regular test execution. Run it from the Shadow CLJS test REPL so the
  measurements use the same JavaScript DataScript implementation as the
  Explorer."
  (:require [datascript.core :as d]
            [eacl.core :as eacl]
            [eacl.explorer.seed :as seed]))

(def default-server-count 10000)
(def default-sample-count 30)
(def default-warmup-count 20)
(defonce !benchmark-run (atom {:state :idle}))

(defn- now-ms
  []
  (.now js/performance))

(defn- percentile
  [sorted-values percentile']
  (let [index (min (dec (count sorted-values))
                   (long (js/Math.floor (* percentile' (count sorted-values)))))]
    (nth sorted-values index)))

(defn- summarize
  [samples invocations]
  (let [sorted-values (vec (sort samples))]
    {:mean-us     (/ (reduce + 0 samples) (count samples))
     :p50-us      (percentile sorted-values 0.50)
     :p95-us      (percentile sorted-values 0.95)
     :min-us      (first sorted-values)
     :max-us      (peek sorted-values)
     :samples     (count samples)
     :invocations invocations}))

(defn- measure!
  [{:keys [run! valid-result? invocations]}
   {:keys [sample-count warmup-count]}]
  (dotimes [_ warmup-count]
    (let [result (run!)]
      (when-not (valid-result? result)
        (throw (ex-info "Benchmark warmup produced an invalid result."
                        {:result result})))))
  (let [samples
        (vec
         (for [_ (range sample-count)]
           (let [!last-result (volatile! nil)
                 started-at   (now-ms)]
             (dotimes [_ invocations]
               (vreset! !last-result (run!)))
             (let [elapsed-us (* 1000.0
                                 (/ (- (now-ms) started-at)
                                    invocations))
                   result     @!last-result]
               (when-not (valid-result? result)
                 (throw (ex-info "Benchmark sample produced an invalid result."
                                 {:result result})))
               elapsed-us))))]
    (summarize samples invocations)))

(defn prepare-runtime!
  "Creates a fresh Explorer runtime with the requested number of servers.
  Seeding is intentionally outside every measured query."
  ([]
   (prepare-runtime! default-server-count))
  ([server-count]
   (let [{:keys [conn client] :as runtime} (seed/create-runtime)
         started-at                         (now-ms)]
     (seed/install-foundation! conn client)
     (doseq [batch (:batches (seed/seed-more-plan (d/db conn) server-count))]
       (seed/execute-batch! conn client batch))
     (assoc runtime
            :seed-ms (- (now-ms) started-at)
            :totals (seed/current-totals (d/db conn))))))

(defn- workloads
  [client page-options]
   [{:name          :can-direct-allow
    :invocations   50
    :valid-result? true?
    :run!          #(eacl/can? client
                               (seed/->user "user-1")
                               :admin
                               (seed/->account "account-0001"))}
   {:name          :can-recursive-allow
    :invocations   50
    :valid-result? true?
    :run!          #(eacl/can? client
                               (seed/->user "user-1")
                               :view
                               (seed/->server "server-0001-0001"))}
   {:name          :can-recursive-deny
    :invocations   50
    :valid-result? false?
    :run!          #(eacl/can? client
                               (seed/->user "user-2")
                               :view
                               (seed/->server "server-0002-0001"))}
   {:name          :lookup-server-page-20
    :invocations   5
    :valid-result? #(= 20 (count (:data %)))
    :run!          #(eacl/lookup-resources
                     client
                     (merge
                      {:subject       (seed/->user "user-1")
                       :permission    :view
                       :resource/type :server}
                      (page-options 20)))}
   {:name          :count-visible-servers
    :invocations   50
    :valid-result? #(= 4000 (:count %))
    :run!          #(eacl/count-resources
                     client
                     {:subject       (seed/->user "user-1")
                      :permission    :view
                      :resource/type :server})}
   {:name          :read-account-server-page-20
    :invocations   10
    :valid-result? #(= 20 (count (:data %)))
    :run!          #(eacl/read-relationships
                     client
                     (merge
                      {:subject/type      :account
                       :subject/id        "account-0001"
                       :resource/type     :server
                       :resource/relation :account}
                      (page-options 20)))}
   {:name          :lookup-known-user-page-20
    :invocations   10
    :valid-result? #(and (= 20 (count (get-in % [:page :data])))
                         (pos? (get-in % [:total :count])))
    :run!
    #(let [query       {:resource     (seed/->platform "platform")
                        :permission   :view
                        :subject/type :user}
           page-query  (merge query (page-options 20))
           count-query (merge query (select-keys page-query [:cache?]))]
       {:page (eacl/lookup-subjects
               client
               page-query)
        :total (eacl/count-subjects
                client
                count-query)})}])

(defn benchmark-workload!
  "Runs one named workload. Keeping workloads independently callable also
  yields the browser event loop between expensive measurements."
  ([client page-options workload-name]
   (benchmark-workload! client page-options workload-name {}))
  ([client page-options workload-name
    {:keys [sample-count warmup-count]
     :or   {sample-count default-sample-count
            warmup-count default-warmup-count}}]
   (let [workload (some #(when (= workload-name (:name %)) %)
                        (workloads client page-options))]
     (when-not workload
       (throw (ex-info "Unknown benchmark workload."
                       {:workload workload-name})))
     (measure! workload
               {:sample-count sample-count
                :warmup-count warmup-count}))))

(defn benchmark-status
  "Returns the status or result of the current asynchronous benchmark."
  []
  @!benchmark-run)

(defn start-benchmark-workload!
  "Runs one workload while yielding to the browser between measured samples.

  This is the preferred entry point for large graphs: a synchronous suite can
  starve Shadow's browser heartbeat even when every individual query is
  acceptably bounded."
  ([client page-options workload-name]
   (start-benchmark-workload! client page-options workload-name {}))
  ([client page-options workload-name
    {:keys [sample-count warmup-count]
     :or   {sample-count default-sample-count
            warmup-count default-warmup-count}}]
   (let [{:keys [run! valid-result? invocations] :as workload}
         (some #(when (= workload-name (:name %)) %)
               (workloads client page-options))
         !samples (volatile! [])]
     (when-not workload
       (throw (ex-info "Unknown benchmark workload."
                       {:workload workload-name})))
     (reset! !benchmark-run
             {:state :running
              :workload workload-name
              :completed-samples 0
              :sample-count sample-count})
     (letfn [(fail! [phase result]
               (reset! !benchmark-run
                       {:state :failed
                        :workload workload-name
                        :phase phase
                        :result result}))
             (run-sample! []
               (let [!last-result (volatile! nil)
                     started-at   (now-ms)]
                 (dotimes [_ invocations]
                   (vreset! !last-result (run!)))
                 {:elapsed-us (* 1000.0
                                 (/ (- (now-ms) started-at)
                                    invocations))
                  :result @!last-result}))
             (sample! [remaining]
               (when (= :running (:state @!benchmark-run))
                 (if (zero? remaining)
                   (reset! !benchmark-run
                           {:state :done
                            :workload workload-name
                            :result (summarize @!samples invocations)})
                   (let [{:keys [elapsed-us result]} (run-sample!)]
                     (if-not (valid-result? result)
                       (fail! :sample result)
                       (do
                         (vswap! !samples conj elapsed-us)
                         (swap! !benchmark-run assoc
                                :completed-samples (count @!samples))
                         (js/setTimeout #(sample! (dec remaining)) 0)))))))
             (warmup! [remaining]
               (when (= :running (:state @!benchmark-run))
                 (if (zero? remaining)
                   (sample! sample-count)
                   (let [result (run!)]
                     (if-not (valid-result? result)
                       (fail! :warmup result)
                       (js/setTimeout #(warmup! (dec remaining)) 0))))))]
       (js/setTimeout #(warmup! warmup-count) 0)
       @!benchmark-run))))

(defn benchmark-client!
  "Benchmarks one client after JIT/cache warmup.

  page-options is a function from page size to the version-specific public
  pagination map, for example (fn [n] {:limit n}) on v7 or
  (fn [n] {:first n}) on v8."
  ([label runtime page-options]
   (benchmark-client! label runtime page-options {}))
  ([label {:keys [client seed-ms totals]} page-options
    {:keys [sample-count warmup-count]
     :or   {sample-count default-sample-count
            warmup-count default-warmup-count}}]
   {:label        label
    :seed-ms      seed-ms
    :totals       totals
    :sample-count sample-count
    :warmup-count warmup-count
    :workloads
    (into {}
          (map (fn [{:keys [name]}]
                 [name (benchmark-workload!
                        client
                        page-options
                        name
                        {:sample-count sample-count
                         :warmup-count warmup-count})]))
          (workloads client page-options))}))
