## ADDED Requirements

### Requirement: Explorer controls the native DataScript cache
EACL Explorer SHALL use one DataScript EACL client per runtime and SHALL inspect and expire that client's native completed-answer cache through the backend's public APIs. The cache SHALL be enabled by default.

#### Scenario: Runtime initialization
- **WHEN** the explorer runtime is initialized
- **THEN** the runtime's EACL client owns the cache used by queries, metrics, and eviction
- **AND** the cache-enabled option is true

### Requirement: Cache option is reactive DataScript state
EACL Explorer SHALL store the current cache-enabled option and query generation in its singleton local DataScript UI entity. Changing either value SHALL invalidate stale query jobs and recompute all visible EACL-backed results using the current option.

#### Scenario: Disable cache
- **WHEN** the user changes the cache toggle from enabled to disabled
- **THEN** the option is transacted into local DataScript
- **AND** every visible EACL-backed result is recomputed with `:cache? false`
- **AND** a job started under the enabled state cannot publish into the disabled state

#### Scenario: Re-enable cache
- **WHEN** the user changes the cache toggle from disabled to enabled
- **THEN** every visible EACL-backed result is recomputed with the client's native completed-answer cache
- **AND** entries retained while the cache was disabled remain eligible for validated reuse

### Requirement: Query-driving filters use the DataScript UI entity
EACL Explorer SHALL use the singleton DataScript UI entity as the live source of truth for the subject, permission, selected resource, cache-enabled option, and query generation. Session storage MAY seed or mirror durable preferences but SHALL NOT be the live query-state authority.

#### Scenario: Filter transaction
- **WHEN** the active subject or permission is changed
- **THEN** the new value is transacted into the UI entity
- **AND** visible counts, pages, detail queries, and expanded child jobs refresh against one coherent query-state snapshot

#### Scenario: Resource selection transaction
- **WHEN** the selected resource changes without changing subject, permission, or cache options
- **THEN** the selection and detail panel update from the DataScript UI entity
- **AND** unrelated resource counts, pages, and child jobs are not invalidated or restarted

### Requirement: User can evict the configured cache
EACL Explorer SHALL provide an **Evict Cache** button that expires the exact native cache used by the EACL client regardless of whether cache use is currently enabled. After expiry, the explorer SHALL advance its query generation and refresh visible results.

#### Scenario: Evict while enabled
- **WHEN** the cache contains entries and the user activates **Evict Cache** while caching is enabled
- **THEN** the native completed-answer cache is expired before queries refresh
- **AND** the first refreshed cache-eligible results are labelled as misses
- **AND** refreshed queries may repopulate the store

#### Scenario: Evict while disabled
- **WHEN** the user activates **Evict Cache** while caching is disabled
- **THEN** the native completed-answer cache is expired
- **AND** refreshed queries continue to bypass cache reads and writes

### Requirement: Every visible EACL result shows cache provenance
EACL Explorer SHALL render a compact textual cache badge in the timing suffix of every visible EACL-backed result value or collection, immediately before the duration. The badge SHALL be green **HIT** for a validated hit, orange **MISS** for enabled direct/recomputed work, and red **CACHE DISABLED** when the current cache option is false.

The schema editor disclosure SHALL NOT render a cache badge because it is a control surface rather than a cache-query result.

#### Scenario: Repeated cacheable query
- **WHEN** an enabled cache-eligible query is displayed once as a computed result and then repeated without a relevant data or query change
- **THEN** the first result displays **MISS**
- **AND** the validated repeated result displays **HIT**

#### Scenario: Disabled query
- **WHEN** a visible query completes while the cache option is false
- **THEN** its result displays **CACHE DISABLED**
- **AND** it does not display **MISS** or **HIT**

#### Scenario: Aggregated query result
- **WHEN** a displayed result combines multiple EACL calls
- **THEN** it displays **HIT** only if at least one cache-eligible call contributed and every contributing EACL call was a hit
- **AND** it displays **MISS** if any contributing call was direct work or a miss while caching is enabled

### Requirement: Cache metrics are available as collapsible EDN
EACL Explorer SHALL provide a compact collapsible cache section containing a labelled slider-style **Cache Enabled: On/Off** control, the **Evict Cache** button, and a pretty-printed EDN code block of the latest backend-native cache metrics. When collapsed, the section SHALL occupy only its single control row. The metrics SHALL refresh after EACL query completion and eviction without triggering another query refresh.

#### Scenario: Inspect metrics
- **WHEN** the user expands the cache section after queries have run
- **THEN** the EDN block shows the current native entry counts and all counters/tier data supplied by the DataScript backend

#### Scenario: Metrics change after query
- **WHEN** an EACL query changes cache hits, misses, puts, occupancy, validation, or eviction counters
- **THEN** the metrics block updates without requiring a manual page reload
- **AND** an expanded metrics disclosure continues to refresh while it is displayed
- **AND** the metrics update does not itself rerun EACL queries

#### Scenario: Metrics provider fails
- **WHEN** reading provider metrics throws an error
- **THEN** the cache section shows a diagnostic error
- **AND** authorization results and cache fallback behavior remain unchanged

### Requirement: Cache controls do not change authorization semantics
Enabling, disabling, observing, or evicting the cache SHALL NOT change the data, ordering, permission decision, or consistency semantics of an EACL query.

#### Scenario: Compare cached and bypassed results
- **WHEN** the same query is evaluated with caching enabled and with caching disabled against the same selected DataScript value
- **THEN** the result payloads are equal after removing cache provenance and timing fields

### Requirement: Explorer cache capacity supports benchmark-scale local data
EACL Explorer SHALL configure its singleton DataScript client with a bounded projection-cache working set sized for the documented 50k–100k local server workflow without changing completed-answer or denotation-cache semantics.

#### Scenario: Runtime projection budget
- **WHEN** the Explorer runtime creates its EACL client
- **THEN** both exact and managed projection stores report a 32 MiB maximum weight
- **AND** the configured stores remain bounded and observable through the cache metrics panel
