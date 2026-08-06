## ADDED Requirements

### Requirement: Portable backends honor the request cache option
EACL DataScript and Datahike clients SHALL accept `:cache?` on cache-aware request maps with the same contract as the Datomic client. An absent or true option SHALL use the client's native completed-answer cache, false SHALL bypass native completed-answer cache reads and writes for only that call, and a non-Boolean value SHALL fail as an invalid request.

#### Scenario: Per-request bypass
- **WHEN** a DataScript or Datahike client has a populated native completed-answer cache and receives a supported request with `:cache? false`
- **THEN** the request computes against the selected database value without reading or writing the native completed-answer cache
- **AND** subsequent cache-enabled calls can still use entries retained before the bypass

#### Scenario: Invalid request cache option
- **WHEN** a supported request supplies a non-Boolean `:cache?` value
- **THEN** EACL throws a typed invalid-request error identifying `:cache?`

#### Scenario: Cache option is not query identity
- **WHEN** otherwise identical enabled and bypassed requests are evaluated
- **THEN** `:cache?` is consumed as execution control and excluded from semantic query keys, filters, and cursors

### Requirement: Permission checks can return cache provenance
EACL SHALL expose an additive detailed permission-check API returning a map containing at least `:allowed?`, `:cached?`, and `:cache-basis`. The existing `can?` API SHALL continue to return a plain Boolean with unchanged semantics.

#### Scenario: Detailed permission miss and hit
- **WHEN** the detailed permission check is called twice with the same enabled, cache-eligible demand and unchanged relevant data
- **THEN** both responses contain the same `:allowed?` decision
- **AND** the first response has `:cached? false`
- **AND** the validated repeated response has `:cached? true`

#### Scenario: Detailed permission bypass
- **WHEN** the detailed permission check receives `:cache? false`
- **THEN** it returns the correct `:allowed?` decision with `:cached? false`
- **AND** it performs no completed-answer cache read or write

#### Scenario: Existing custom authorization implementation
- **WHEN** a caller uses an existing `IAuthorization` implementation that does not implement the optional detailed-check protocol
- **THEN** the compatibility helper computes the Boolean decision through `can?`
- **AND** reports `:cached? false` without breaking protocol construction

### Requirement: Portable local-store stats expose capacity and evictions
The backend-neutral EACL local cache store SHALL expose additive metrics sufficient to observe current occupancy, configured entry capacity, capacity eviction, explicit eviction/clear, and operation-kind distribution. Existing metric keys SHALL retain their meanings.

#### Scenario: Capacity eviction
- **WHEN** storing a new entry takes the portable local store above `:max-entries`
- **THEN** the store deterministically removes an entry
- **AND** `stats` reports the incremented capacity eviction count
- **AND** current entries do not exceed the configured capacity

#### Scenario: Per-kind occupancy
- **WHEN** cache entries for different EACL operation kinds are stored
- **THEN** `stats` reports current entries and counters by operation kind where the semantic key supplies that kind
- **AND** reports the occupancy and capacity of the local tier

#### Scenario: Clear store
- **WHEN** `clear!` is called on a populated portable local store
- **THEN** current entries and per-kind occupancy become zero
- **AND** cumulative request counters remain available
- **AND** explicit/manual eviction telemetry reflects the cleared entries

### Requirement: Native completed-answer cache remains directly observable
EACL DataScript SHALL expose public backend APIs for inspecting and expiring the client-private native completed-answer cache without requiring callers to access client internals. Custom portable provider metrics SHALL remain provider-native.

#### Scenario: Backend cache lifecycle
- **WHEN** a caller evaluates cache-aware queries through a DataScript client
- **THEN** `eacl.datascript.core/cache-stats` reflects native completed-answer cache operations
- **AND** `eacl.datascript.core/expire-cache!` makes prior native entries unreachable
