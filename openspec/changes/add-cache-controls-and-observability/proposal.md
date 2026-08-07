## Why

EACL Explorer currently uses EACL v8's default cache invisibly, so users cannot compare cached and uncached behavior, clear retained answers, or see whether a displayed authorization result was reused. Exposing cache controls and provenance turns the explorer into a useful demonstration and diagnostic surface for EACL's authenticated cache.

## What Changes

- Add an enabled-by-default cache toggle and an **Evict Cache** action to EACL Explorer.
- Store query-driving UI filters/options, including cache enablement and a cache refresh generation, in the local DataScript database so a change invalidates in-flight UI work and refreshes every visible query result.
- Control the DataScript client's native v8 completed-answer cache through its public eviction and metrics APIs, and render those current metrics as formatted EDN in a collapsible cache section.
- Add a compact provenance badge before each visible EACL-backed result: green **HIT**, orange **MISS**, or red **CACHE DISABLED**.
- Preserve per-query cache provenance through synchronous result builders and asynchronous count/child-section jobs, including aggregated results that execute more than one EACL call.
- Bring portable EACL backends in line with the documented per-request `:cache?` contract, expose the native DataScript cache through backend APIs, and extend backend-neutral local-store telemetry additively.

## Capabilities

### New Capabilities

- `explorer-cache-controls`: Cache enablement, eviction, reactive query refresh, result badges, and the collapsible metrics UI in EACL Explorer.
- `portable-cache-observability`: Portable EACL cache bypass and telemetry behavior needed by DataScript consumers that explicitly supply a cache store.

### Modified Capabilities

None. EACL Explorer has no existing OpenSpec capability specifications.

## Impact

- EACL Explorer: `seed.cljs`, `state.cljs`, `explorer.cljs`, `core.cljs`, `index.css`, and their ClojureScript tests.
- EACL: additive changes to the backend-neutral cache store metrics and DataScript/Datahike request-cache handling/provenance APIs, with contract tests; no authorization result semantics change.
- Dependency coordination: EACL Explorer must advance its EACL git SHA to the EACL revision containing the portable cache additions.
- Runtime state: query-driving UI state moves from the Rum application atom/session-only path into a dedicated local DataScript UI entity; transient job/render state remains in the application atom.
