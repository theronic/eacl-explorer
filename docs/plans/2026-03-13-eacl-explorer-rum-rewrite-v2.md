# Plan v2: Foundational Rewrite of `eacl-datastar` into `eacl-explorer-rum`

## Current Status

Workspace:
`/Users/petrus/.codex/worktrees/b3b4/eacl-datastar`

Completed on 2026-03-13:

- Replaced the old server/DataStar/Datomic source tree with a cljs-first Rum +
  DataScript explorer in `src/eacl_explorer_rum/`.
- Removed the last dev-only `.clj` helper namespaces from `src-dev/`; the app
  surface under `src/`, `resources/public/`, and `test/` is now cljs/html/css/js
  only.
- Added `shadow-cljs` app and browser-test builds, a new static shell, and
  browser-side cljs tests.
- Rewrote the seed dataset for DataScript with `:benchmark = 100,000` servers
  and `:smoke = 492` servers.
- Removed the old `src/electric_starter_app` and `test/electric_starter_app`
  trees.
- Ran the cljs suites through the browser-backed shadow REPL:
  `12 tests, 53 assertions, 0 failures, 0 errors`.
- Manually validated the smoke build at
  `http://localhost:18091/index.html?seed-profile=smoke`:
  subject panel, resource expansion, detail panel, and schema toggle all work.

Remaining before close-out:

- Clean up repo ergonomics around generated shadow output and any ignored local
  files.
- Decide whether to keep the current public asset moves as plain adds/deletes or
  restage them as renames.
- Recheck the default benchmark page after a clean browser load if we want a
  final end-to-end timing note for the 100k seed.

## Next Steps

1. Update `.gitignore` or cleanup so generated `resources/public/js` output does
   not pollute the diff.
2. Do one final `git status` review in the `b3b4` worktree and make sure the
   remaining diff is only intentional source/docs/assets.
3. If benchmark timing matters for the handoff, let the default
   `http://localhost:18091/index.html` page finish seeding and record the
   observed ready state.
4. Resume all further work in
   `/Users/petrus/.codex/worktrees/b3b4/eacl-datastar`, not in
   `/Users/petrus/Code/eacl-datastar`.

## Summary

The rewrite should treat a browser-only Rum + DataScript architecture as the
foundational assumption, not as a transport swap from the current server model.

The target system is organized into four explicit layers:

1. dataset specification,
2. explorer query derivation,
3. client runtime and invalidation orchestration, and
4. Rum rendering.

This version addresses the critique by making the new cljs-first source set
canonical early, splitting seed topology from execution, and defining an
explicit rerun model for all query-derived UI.

## Design Rules

- All user-visible explorer panels are derived from `(db, ui-state)`.
- Rum owns rendering only; it does not own business logic.
- All seed topology and explorer derivation lives in `.cljs` because the app is
  purely client-side; only dev tooling stays on the JVM.
- DataScript db changes invalidate per-revision memoized derivations and restart
  deferred count jobs.
- The new cljs-first build surface becomes canonical before most migration work
  continues.

## Phases

### Phase 1: Make the new cljs-first source set and build surface canonical

- [x] Rewrite `deps.edn` around the new architecture: keep Clojure, add
      ClojureScript and Rum, add `shadow-cljs` in the dev/build path, and swap
      the EACL dependency to the v7 module roots in
      `/Users/petrus/.codex/worktrees/e264/eacl`.
- [x] Add `shadow-cljs.edn` with browser builds for the app and cljs test page,
      serving `resources/public` on port `18091`.
- [x] Replace the old DataStar HTML shell with a minimal static shell that loads
      CSS, `graph.js`, and the compiled Rum bundle.
- [x] Create the new canonical namespaces: a cljs dataset layer, a cljs
      explorer layer, a cljs runtime/state layer, and a cljs Rum entrypoint.
- [x] Stop the old server path from being the active development path once the
      new build boots, so all subsequent work happens against the new browser
      architecture.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 2: Split seed topology from DataScript execution

- [x] Move the benchmark topology into pure cljs batch-building functions that
      describe accounts, teams, VPCs, servers, and relationship intents without
      embedding backend execution.
- [x] Implement a small DataScript executor that consumes those batches,
      transacts entity data, writes EACL relationships through the DataScript
      client, and records seed metadata.
- [x] Redefine `:benchmark` to `50` accounts × `2,000` servers/account so the
      seeded total is exactly `100,000`, while preserving the current per-account
      and per-user shape used by the UI.
- [x] Keep `:smoke` as the small profile and make both profiles available to
      browser-based cljs tests and runtime code through the same
      `profile-config`.
- [x] Make browser seeding cooperative by yielding between server batches; the
      runtime should expose seeding progress and final totals separately from the
      explorer UI.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 3: Rebuild explorer derivation as pure cljs over `(db, ui-state)`

- [x] Port known-user pagination, available permissions, resource hydration,
      top-level group lookup, nested child grouping, detail permission lookup,
      schema graph construction, and duration formatting into pure explorer
      functions.
- [x] Keep top-level resource pagination on EACL opaque cursors returned by the
      DataScript client so browser behavior matches the current explorer.
- [x] Keep nested section pagination local to each expanded section by storing
      the last visible child id for that section, matching the current feature
      semantics without introducing extra EACL APIs.
- [x] Replace basis-t caches with per-`db-rev` memoization for schema-derived
      support data such as permissions-by-type and relation indexes.
- [x] Define the invalidation contract explicitly:
      any `db-rev` change invalidates memoized support data,
      any `db-rev`, subject, or permission change resets deferred top-level
      counts to `pending`,
      and all synchronous panel derivations recompute from the latest `(db, ui)`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 4: Build the client runtime and deferred count orchestration

- [x] Introduce one client app-state atom with separate domains for bootstrap,
      UI, deferred counts, and db revision rather than porting the old view
      registry or server-side signal model.
- [x] Register a `ds/listen!` hook once at runtime startup to increment
      `:db-rev` and trigger rerender invalidation.
- [x] Seed the benchmark dataset before exposing the full explorer view; the UI
      may show a loading shell and progress state during bootstrap, but normal
      browsing begins only after seeding completes.
- [x] Implement deferred top-level counts as cooperative cljs jobs keyed by
      `[db-rev subject-id permission resource-type]`; only one live job may own
      a key at a time, and stale job results must be ignored.
- [x] Use stored seed totals for the header stat pill and reserve EACL counting
      only for context-dependent resource-group counts.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 5: Rebuild the UI in Rum as a thin rendering layer

- [x] Port the header, subject panel, resource panel, detail panel, and schema
      shell into Rum components that consume pre-derived data and dispatch simple
      state mutations.
- [x] Preserve the existing copy, class names, layout structure, schema toggle,
      quick-subject behavior, permission chips, selection highlighting, and depth
      cap so the current CSS and graph asset remain valid with minimal changes.
- [x] Keep session storage only for `subject-id` and `permission`; remove
      DataStar-specific session fields such as `viewId`.
- [x] Trigger `window.EaclSchemaGraph.render(...)` from Rum lifecycle hooks when
      the schema panel opens or its derived graph data changes.
- [x] Keep all visible timings as client-side query durations so the interface
      still communicates cost even though the app is no longer server-rendered.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 6: Remove obsolete code and validate the new system

- [x] Delete the old Ring/DataStar/Datomic runtime namespaces, tests, and public
      assets once the Rum app covers the same feature surface.
- [x] Rewrite the README and dev instructions around the browser build and the
      new DataScript-based tests.
- [x] Replace Datomic test fixtures with DataScript fixtures and run the new
      cljs suites through a browser-backed cljs REPL over nREPL.
- [x] Validate smoke seeding, benchmark totals, known-user pagination,
      top-level opaque-cursor paging, nested section paging, detail permission
      lookup, schema graph data, and deferred-count invalidation.
- [x] Boot the browser app and confirm parity on
      `http://localhost:18091/index.html`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.
