## 1. Portable EACL Cache Contract

- [x] 1.1 Add failing backend contract tests in EACL for DataScript/Datahike `:cache?` true, false, invalid values, cache-key exclusion, and retained-entry reuse after bypass.
- [x] 1.2 Implement a shared request-cache option validator and apply the bypass to DataScript and Datahike map-form permission checks, lookups, counts, and relationship reads without changing cursor identity.
- [x] 1.3 Add failing EACL tests for a non-breaking detailed permission-check result API, including miss, hit, bypass, and fallback behavior for existing `IAuthorization` implementations.
- [x] 1.4 Implement the optional detailed-check protocol/helper and backend result plumbing for Datomic, DataScript, and Datahike while keeping every `can?` arity Boolean.
- [x] 1.5 Add failing portable `LocalStore` tests for maximum capacity, capacity evictions, manual clear/eviction, per-kind occupancy/counters, and cumulative metrics after clear.
- [x] 1.6 Extend backend-neutral `LocalStore` stats additively with capacity, eviction, manual-eviction, per-kind, and local-tier occupancy data while preserving custom `CacheStore` behavior.
- [x] 1.7 Update EACL cache/API documentation for the detailed permission result and portable metrics, then run the affected EACL CLJ/CLJS backend tests through nREPL.

## 2. Explorer Runtime and Query State

- [x] 2.1 Advance both EACL dependency SHAs in `eacl-explorer/deps.edn` to the tested EACL revision containing Section 1.
- [x] 2.2 Keep one DataScript EACL client per runtime and target its native completed-answer cache through `cache-stats` and `expire-cache!`.
- [x] 2.3 Add namespaced DataScript schema attributes and singleton initialization for subject, permission, selected resource, cache-enabled default true, and query generation.
- [x] 2.4 Replace live `!app` reads/writes for query-driving options with DataScript entity reads/transacts while retaining session storage only as bootstrap/mirroring and transient presentation state in `!app`.
- [x] 2.5 Include cache-enabled state and query generation in count/child job contexts so the existing DataScript listener invalidates and restarts every visible query without allowing stale publications.
- [x] 2.6 Implement cache toggle and eviction state actions; eviction must call `eacl.datascript.core/expire-cache!` before advancing query generation and must work while caching is disabled.

## 3. Query Provenance and Metrics

- [x] 3.1 Add explorer helpers that attach `:cache?` to EACL requests and normalize enabled hit/miss or disabled provenance without allowing control data into displayed query identity.
- [x] 3.2 Preserve provenance from lookup/count response maps in top-level resource counts/pages and detail permission rows.
- [x] 3.3 Use the detailed permission-check API in child authorization paths and conservatively combine relationship reads and permission checks so any direct work/miss makes the aggregate a miss.
- [x] 3.4 Preserve provenance for known-user, child-page, child-total, empty, error, and unavailable visible result states with deterministic aggregation rules.
- [x] 3.5 Implement coalesced backend-native `cache-stats` snapshots in transient app state after EACL work and eviction, including error capture and live polling while displayed, without transacting metrics or retriggering queries.
- [x] 3.6 Add pure/unit tests for provenance normalization, aggregate hit rules, disabled precedence, and pretty-printed metrics data.

## 4. Cache User Interface

- [x] 4.1 Add a compact collapsible cache section near the schema section with an accessible slider-style enabled toggle, **Evict Cache** button, and pretty-printed EDN metrics code block.
- [x] 4.2 Add text-first **HIT**, **MISS**, and **CACHE DISABLED** badge components and styles with green hit, orange miss, and red disabled treatments.
- [x] 4.3 Place the relevant badge immediately before timing within every visible EACL-backed count, page/range, list, relationship result, detail row, and aggregated child result; the schema editor disclosure does not show cache provenance.
- [x] 4.4 Add Rum rendering tests for control state, compact disclosure behavior, slider semantics and labels, exact badge labels/classes, eviction wiring, and metrics/error rendering.

## 5. Verification

- [x] 5.1 Add explorer state tests proving DataScript is the live query-option authority, toggles restart all query jobs, old-context jobs are rejected, eviction expires the native cache, and UI-only datoms do not change authorization answers.
- [x] 5.2 Add explorer integration tests proving first enabled calls show misses, unchanged repeats show hits, disabled calls neither read nor write the store, re-enabling can reuse retained entries, and eviction makes the next enabled results miss.
- [x] 5.3 Run the main EACL Explorer ClojureScript test namespaces and compile both browser builds through nREPL.
- [x] 5.4 Manually verify in the browser that all visible query surfaces update immediately across enable, disable, re-enable, and eviction; confirm the cache disclosure is compact when collapsed, metrics occupancy/evictions, switch state, and badge transitions match the store.

## 6. Saturated Cache Performance

- [x] 6.1 Reproduce the cache-enabled append regression with a full managed projection tier and record cache-on, bypass, page, eviction, and victim-probe telemetry.
- [x] 6.2 Replace EACL's repeated full-tier LRU victim scans with an exact ordered access log, amortized constant-time touches, bounded compaction, and deterministic regression tests.
- [x] 6.3 Configure the Explorer's singleton EACL client with a bounded 32 MiB projection working set for 50k–100k local datasets and advance both EACL dependency SHAs.
- [x] 6.4 Normalize completed lookup-page keys after cursor authentication so signed snapshot transport and recovery instructions do not fragment identical pages; retain the internal boundary and prove its separation in Dafny.
- [x] 6.5 Re-run EACL CLJ/CLJS and formal verification, the Explorer browser suite, and the saturated append/page/subject-switch browser acceptance flow.
