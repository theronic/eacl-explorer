## Context

EACL Explorer is a Rum/ClojureScript application backed by one in-memory DataScript connection. Its runtime retains `{:conn ... :client ...}` and `eacl.datascript.core/make-client` creates a client-private current-generation cache. DataScript exposes that exact cache through `cache-stats` and `expire-cache!`; supplying a portable `CacheStore` does not replace the client-private completed-answer cache. Query-driving UI values live in `!app`, while a DataScript listener increments `:db-rev`, invalidates asynchronous results, and restarts count and expanded-child jobs after database changes.

EACL v8 already supports an explicitly supplied backend-neutral `eacl.cache/CacheStore`. Lookup and count responses carry `:cached?`, while `can?` deliberately returns a Boolean. The portable local store exposes `stats` and `clear!`, but its current stats omit capacity and eviction counters. Datomic implements the documented per-request `:cache?` bypass; DataScript and Datahike do not yet consume it even though the public documentation describes a cross-backend contract.

The displayed explorer data comes from several shapes of EACL work:

- direct lookup/count result maps;
- schema and relationship reads that are not completed-answer cache hits;
- Boolean permission checks used while building child sections;
- asynchronous jobs that aggregate multiple calls.

The design therefore needs to target the native client cache, define a uniform per-call provenance model, and establish a reactive boundary that cannot create a render/query loop.

## Goals / Non-Goals

**Goals:**

- Let users enable or bypass the configured result cache without rebuilding the DataScript database or losing retained entries.
- Clear the exact cache used by the explorer and immediately recompute visible results.
- Show testable `HIT`, `MISS`, and `CACHE DISABLED` provenance on every visible EACL-backed result unit.
- Display live, provider-native cache metrics as readable EDN, including capacity and eviction behavior for the portable local store.
- Make query-driving filter/options changes transactional in local DataScript so current asynchronous work becomes stale and all visible queries refresh coherently.
- Preserve EACL authorization and consistency semantics when cache behavior or observability changes.

**Non-Goals:**

- Persisting the cache or DataScript database across a full browser reload.
- Adding a remote/distributed cache provider to the explorer.
- Building charts, alerting, or a stable cross-provider metrics normalization layer.
- Treating TTL, counters, or UI state as cache-validity evidence.
- Moving transient presentation state such as open panels, drafts, and in-flight job payloads into DataScript.

## Decisions

### 1. Control the client's native cache

`seed/create-runtime` and the shared runtime construct one DataScript EACL client. The enabled toggle controls per-request use of that client's native cache; it does not replace the client or allocate another cache. Eviction uses `eacl.datascript.core/expire-cache!`, and metrics use `eacl.datascript.core/cache-stats`.

This preserves entries while the cache is disabled, makes re-enabling capable of producing a legitimate hit, and targets the exact cache serving completed answers. Recreating cached and uncached clients or clearing a caller-supplied portable provider was considered, but either duplicates derived state or misses the private v8 completed-answer cache.

### 2. Use the documented request-level bypass

Every EACL call issued for a visible result will receive the current `:cache?` option. DataScript and Datahike will implement the same validation and bypass behavior as Datomic:

- absent or `true` uses the client's native completed-answer cache;
- `false` reads and writes no completed-answer cache entry for that call;
- any non-Boolean value is an invalid request;
- `:cache?` is control data and is excluded from semantic cache keys and authorization filters.

The map arity of `can?` is used wherever a Boolean-only check is sufficient. A separate, additive `eacl.core/check-permission` result API will expose `{:allowed? ... :cached? ... :cache-basis ...}` for callers such as the explorer that need per-call provenance. It will be implemented through a separate optional protocol so existing external `IAuthorization` implementations are not broken; `can?` remains Boolean.

### 3. Define one UI provenance model

Explorer query wrappers will convert EACL response metadata plus the current cache option into one of:

- `:hit` when the cache is enabled and EACL reports `:cached? true`;
- `:miss` when the cache is enabled and the result was computed directly, is not cache-eligible, or any constituent call in an aggregate was not a hit;
- `:disabled` whenever the cache option is false.

An aggregate is a hit only when it contains at least one cache-eligible call and every EACL call contributing to the displayed answer reports a hit. This conservative rule prevents a partially recomputed child section from being labelled as a hit. Relationship reads are direct work and therefore render as misses while caching is enabled. The schema editor disclosure is a control surface rather than a cache query result and does not render a provenance badge.

Result builders preserve provenance alongside timing and data. Asynchronous count and child-section job contexts include the query generation and cache option, so a completion from the previous toggle state cannot publish into the current UI.

Deferred child-page timers begin inside the scheduled job and stop before
presentation-only hydration/rendering. The displayed duration therefore
measures EACL query and authorization work rather than unrelated event-loop or
Rum rendering delay; this distinction is essential when interpreting a cache
HIT.

### 4. Put canonical query options in a DataScript UI entity

The explorer schema will add a singleton UI entity with separate attributes for:

- subject id;
- permission;
- selected resource type/id;
- cache enabled flag;
- query generation.

Session storage may seed and mirror the existing subject/permission preferences, but the DataScript entity is the live source of truth. Transacting a subject, permission, or cache option activates the existing connection listener, advances `:db-rev`, invalidates counts and child sections, and restarts current jobs. A selected-resource-only transaction advances a dedicated presentation revision so the detail and selection highlight update without invalidating unrelated resource counts/pages or restarting their jobs. An eviction expires the native cache first and then increments the query generation in the same UI-state path, guaranteeing that the first refreshed cache-enabled calls are misses.

Pagination stacks, panel expansion, schema draft text, seed input, bootstrap state, cached metrics snapshots, and in-flight jobs remain in `!app`. Moving every presentation value into DataScript would add datoms without improving query coherence.

### 5. Refresh metrics independently of query invalidation

The cache section will render the latest successful `eacl.datascript.core/cache-stats` value as pretty-printed EDN, wrapped with explorer state such as `:enabled?`. Query wrappers and asynchronous job publishers will schedule a coalesced metrics refresh into `!app` after EACL work. The cache panel subscribes only to that derived metrics cursor.

Metrics changes will not transact the DataScript UI entity. Doing so after every lookup/store would make the DataScript listener rerun the queries that changed the metrics and create a feedback loop. Metrics failures are shown as diagnostic EDN/error text and do not affect authorization results.

### 6. Extend portable local-store telemetry additively

The backend-neutral local store will retain its existing counters and add:

- `:max-entries`;
- capacity `:evictions`;
- `:manual-evictions` for explicit key removal/clear;
- current `:entries-by-kind` and per-kind counters when the semantic key exposes an operation kind;
- a single local-tier occupancy description.

Counters remain cumulative across `clear!`, while current occupancy becomes zero. Custom `CacheStore` implementations remain free to return provider-native maps; the explorer prints the map rather than depending on every optional key.

### 7. Render controls and badges as text-first UI

The cache controls and metrics disclosure will sit in a dedicated, compact collapsible section near the existing schema disclosure. Its enabled control uses a labelled slider-style switch with explicit on/off text. Badges appear in the result's timing suffix, immediately before the duration, except for the schema editor disclosure. Text labels carry the meaning, with green/orange/red styling as reinforcement, so the state is not communicated by color alone. While the metrics disclosure is open, the provider-native snapshot is polled independently so counters remain visibly current without transacting DataScript or rerunning authorization queries.

## Risks / Trade-offs

- **[Cross-repository sequencing]** The explorer depends on EACL by git SHA, while these specs live in `eacl-explorer` as requested. → Land and test the additive EACL API first, then advance both EACL dependency SHAs in the explorer before its UI changes.
- **[UI datoms enter the authorization database]** The local DataScript connection now contains explorer-only attributes. → Use an `:explorer.ui/*` namespace and keep EACL dependency proofs scoped to schema/relationship data; add regression tests showing UI transactions do not change authorization answers.
- **[Metrics updates race with concurrent jobs]** Several asynchronous jobs can finish close together. → Read the client cache atomically through `cache-stats`, coalesce refresh requests, and publish only complete snapshots.
- **[Mixed-call results can overstate reuse]** A child section may combine relationship reads and many permission checks. → Apply the conservative aggregate rule: any direct work or miss makes the displayed result a miss.
- **[Eviction refresh immediately repopulates the cache]** Expiring while caching is enabled causes visible queries to write fresh entries. → Define the button as “expire then refresh”; tests assert zero native entries immediately after `expire-cache!` and misses on the first refreshed results, not a permanently empty cache.
- **[Portable and Datomic stats differ]** Provider-native metrics are intentionally not identical. → Keep the EDN renderer generic and require only the portable local-store additions used by this demo.

## Migration Plan

1. Implement and test the additive EACL portable-store telemetry, request bypass parity, and detailed permission-check result API across supported backends.
2. Publish/merge that EACL revision and update both EACL dependency SHAs in `eacl-explorer`.
3. Add the explorer native-cache control wiring and DataScript UI-state entity, migrating session values into the entity during initialization.
4. Route query calls through provenance-aware wrappers, add the controls/metrics/badges, and update asynchronous job contexts.
5. Run EACL backend contract tests and the explorer ClojureScript test/compile workflow through nREPL, then manually verify repeated queries, toggling, eviction, metrics, and badge transitions.

Rollback is dependency-first in reverse: revert the explorer UI/runtime changes and SHA bump, then revert the unused additive EACL API if desired. No stored user data migration is required because the explorer database and cache are in-memory.

## Open Questions

None. Provider-specific optional metrics will be displayed verbatim rather than standardized in this change.
