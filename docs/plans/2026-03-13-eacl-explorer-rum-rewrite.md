# Plan: Rewrite `eacl-datastar` as `eacl-explorer-rum`

## Summary

This rewrite will replace the current Ring + DataStar + Datomic explorer with a
browser-only Rum application backed by DataScript and the EACL v7 DataScript
client from `/Users/petrus/.codex/worktrees/e264/eacl`.

The rewritten app will:

- run entirely in the browser,
- preserve the current UI structure and feature set,
- seed a `:benchmark` dataset of `100,000` servers instead of `700,000`, and
- remove Datomic, DataStar, and other server-only dependencies.

## Architecture Changes

- Replace the current server-oriented namespaces with a cljs-first app rooted at
  `eacl_explorer_rum`.
- Add a `shadow-cljs` browser build that serves `resources/public` at
  `http://localhost:18090/`.
- Create a DataScript runtime that owns the conn, wraps it with
  `eacl.datascript.core/make-client`, and exposes a db revision signal for
  reactive reruns.
- Port the current explorer query logic into pure cljs functions over
  `(db acl ui-state)`.
- Port the seed logic to DataScript and run it in cooperative browser batches.
- Rebuild the current HTML panels as Rum components while preserving the CSS and
  D3 schema graph asset.

## Phases

### Phase 1: Establish the new client-only foundation

- [ ] Replace `deps.edn` so the project depends on Clojure, ClojureScript, Rum,
      `shadow-cljs`, and the EACL + EACL DataScript modules from the v7 worktree.
- [ ] Add `shadow-cljs.edn` with a single browser build for the explorer and a
      static dev host on port `18090`.
- [ ] Replace the old DataStar HTML shell with a minimal `resources/public`
      shell that loads the compiled Rum application and the schema graph asset.
- [ ] Create the base `eacl_explorer_rum` namespaces for runtime bootstrap,
      state, seeding, and explorer derivation.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 2: Port the data model and browser seed

- [ ] Convert the current seed fixture topology to DataScript while preserving
      the schema DSL, object IDs, quick subjects, and authorization semantics.
- [ ] Redefine the `:benchmark` seed profile to `100,000` servers by using `50`
      accounts and keeping `2,000` servers per account.
- [ ] Keep `:smoke` as the small fixture profile for tests.
- [ ] Add cooperative seeding so large browser inserts yield between batches.
- [ ] Persist seed metadata in DataScript so the header stat pill can use the
      known server total without recomputing it via EACL.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 3: Rebuild explorer queries and state handling

- [ ] Port known-user pagination, top-level resource lookup, nested relationship
      sections, detail lookup, schema graph data, and duration formatting into
      pure explorer functions.
- [ ] Keep top-level pagination on EACL opaque cursors and keep nested section
      pagination on section-local last-id cursors.
- [ ] Replace the old DataStar signal/view state with a single client app state
      that tracks subject, permission, selection, pagination, expansion, schema
      visibility, db revision, and lazy count status.
- [ ] Recreate lazy top-level counts as client-side cooperative jobs keyed by
      `[subject-id permission db-rev]`.
- [ ] Reset and rerun derived queries when the DataScript db revision changes.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 4: Rebuild the UI in Rum and remove obsolete server code

- [ ] Recreate the header, subject panel, resource panel, detail panel, and
      schema toggle in Rum while preserving the current markup structure and CSS
      classes as closely as possible.
- [ ] Keep the current typography, copy, colors, and schema graph behavior.
- [ ] Preserve session storage for the selected subject and permission.
- [ ] Delete the old Ring/DataStar/Datomic runtime namespaces, tests, and
      assets that are no longer used.
- [ ] Update the README and dev entrypoints to document the new browser-only
      workflow.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 5: Validate the rewrite

- [ ] Replace the Datomic-based tests with DataScript-based tests run through
      nREPL.
- [ ] Cover seed totals, known-user pagination, resource pagination, nested
      sections, detail lookup, schema graph data, and count invalidation.
- [ ] Start the app in the browser build, verify it serves on `localhost:18090`,
      and confirm parity with the current explorer behavior.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.
