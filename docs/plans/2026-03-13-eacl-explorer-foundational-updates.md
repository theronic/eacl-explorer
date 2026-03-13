# Plan: Runtime Persistence, Schema Shell Refresh, and Nested Query Responsiveness

## Summary

This plan addresses six changes together because they share the same fault line:
the explorer currently treats expensive nested authorization work as render-time
derivation instead of explicit runtime state.

The target outcome is:

- the app runtime survives hot reloads,
- the schema editor is always present as a collapsible shell,
- seeding interactions feel native,
- generated users are easier to discover in the subject directory, and
- nested resource sections stop blocking the UI while authorization work runs.

## Foundational Design

- The app should own one persistent browser runtime for development and release
  code, while tests keep isolated runtimes.
- Rum should render from already-derived state. It should not execute expensive
  nested EACL traversals during component render.
- Nested child sections should become explicit asynchronous query state keyed by
  parent resource, child resource type, subject, permission, and db revision.
- `eacl-datascript` should use its tuple indexes when reading anchored
  relationships instead of scanning all relationship datoms.

## Phases

### Phase 1: Make the browser runtime persistent and reshape UI state around long-lived explorer state

- Add a shared runtime in `eacl.explorer.seed` using `defonce conn` and
  `defonce client`, plus a shared-runtime accessor for the app.
- Keep `create-runtime` as the fresh isolated constructor used by tests and ad
  hoc analysis.
- Update app initialization to reuse the shared runtime so DataScript state
  survives hot reloads.
- Rename schema visibility state from `:show-schema?` to `:schema-expanded?`
  and introduce explicit child-section request state for async nested results.
- Reset and invalidate derived nested section request state whenever db revision,
  subject, or permission changes.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 2: Redesign the header, seeding flow, known-user visibility, and schema shell as if they were foundational

- Change the ready subtitle to `EACL Explorer backed by a client-side
  DataScript. Also supports Datomic Pro & Datomic Cloud.`
- Make the seed controls a real form so pressing Enter in the seed input submits
  the same path as the `Seed DB` button.
- Redesign known-user ordering around user roles rather than raw lexical id
  order so newly created owners and shared admins appear on the first page.
- Reset the subject-directory page to page 1 after successful interactive
  seeding.
- Remove the separate `Show Schema` button from the header.
- Always render the schema shell directly under the header, collapsed by
  default, with a caret and compact collapsed height.
- Move `Write Schema` and schema errors to the bottom of the editor pane.
- Change the schema editor copy from `Live schema` to `Edit the Spice schema and
  click Write Schema`.
- Keep `Write Schema` disabled whenever the draft matches the committed schema
  or a write is already in progress.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 3: Move nested child authorization work out of Rum render and make it cooperative

- Replace synchronous child-group derivation in `explorer.cljs` with cheap
  child-group shells that only read schema metadata during render.
- When a child section expands or pages, schedule an asynchronous child-section
  job instead of deriving totals inline.
- Keep the top-level group lookup synchronous for now because the observed cost
  is low relative to nested sections.
- For each child-section job, page anchored relationships outside render,
  publish the current page and exact total back into app state with job-id and
  context guards, and ignore stale work.
- Do not persist full authorized result sets; rerun the live section query for
  page changes against the indexed relationship path.
- Chain nested refreshes so already-expanded child resources reload their own
  child groups after parent jobs finish.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 4: Fix the underlying relationship read path in `eacl-datascript`

- Update `datascript-read-relationships` to normalize cursor ids the same way it
  already normalizes subject/resource ids.
- Update `impl/read-relationships` to use the forward relationship tuple index
  for anchored subject-side queries and the reverse index for anchored
  resource-side queries.
- Honor existing `:limit` and `:cursor` inputs for indexed reads so callers can
  iterate anchored relationships incrementally without scanning the whole graph.
- Keep the current scan-and-filter implementation as a fallback for broad query
  shapes that cannot use an index.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 5: Verify behavior at the UI, explorer, and integration levels

- Extend cljs tests for shared runtime behavior, schema shell defaults, seed-on
  Enter, known-user ordering, and child-section async state transitions.
- Add integration coverage for `eacl/read-relationships` limit/cursor behavior
  through the local DataScript client.
- Run the existing browser cljs suites through nREPL and manually verify the
  resource expansion path in the browser app.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.
