# Plan v2: Foundational Redesign for Runtime Persistence, Schema UX, and Nested Explorer Queries

## Summary

This version upgrades the initial plan by treating each requested change as if
it had been a foundational assumption from the first version of the explorer.

The resulting design has five explicit foundations:

- one persistent browser runtime for the live app,
- isolated runtimes for tests,
- an always-mounted collapsible schema shell,
- a role-aware subject directory optimized for seeded exploration, and
- explicit asynchronous child-section query state backed by indexed anchored
  relationship reads.

## Architecture Rules

- The app runtime boundary lives in `eacl.explorer.seed`, not in view state.
- Rum renders from state and cheap schema metadata only. It does not execute
  nested authorization scans during render.
- Nested child sections are runtime-managed query jobs keyed by section and
  query context, with stale-result guards.
- Page navigation inside a child section reruns the live anchored query for the
  requested cursor; the app retains only current request state, not cached
  authorized inventories.
- `eacl-datascript` must use its tuple indexes for anchored relationship reads;
  scan-and-filter is a fallback, not the default.

## Phases

### Phase 1: Canonicalize the runtime boundary and state model

- Add `defonce conn` and `defonce client` in `eacl.explorer.seed`, plus
  `shared-runtime`.
- Keep `create-runtime` as the isolated constructor used by tests and manual
  analysis.
- Update `initialize-runtime!` to consume the shared runtime and preserve the
  live DataScript DB across hot reloads.
- Replace `:show-schema?` with `:schema-expanded?`.
- Add explicit child-section request state with entries keyed by section key and
  containing:
  status, job-id, parent identity, child resource type, subject, permission,
  db revision, current cursor, current page items, total, elapsed time, and
  error.
- Invalidate child-section request state on db revision, subject change, and
  permission change.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 2: Fix anchored relationship access in `eacl-datascript`

- Update `datascript-read-relationships` to normalize `:cursor` object ids into
  internal entity ids in the same direction as the anchored query.
- Update `impl/read-relationships` to use the forward tuple index when
  `:subject/type`, `:subject/id`, `:resource/type`, and `:resource/relation` are
  present.
- Update `impl/read-relationships` to use the reverse tuple index when
  `:resource/type`, `:resource/id`, `:subject/type`, and `:resource/relation`
  are present.
- Honor `:limit` and `:cursor` for indexed anchored reads so callers can
  iterate incrementally and derive the next cursor from the last relationship.
- Preserve scan-and-filter fallback behavior for broad query shapes that do not
  match an indexed anchored form.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 3: Redesign nested explorer queries as live asynchronous jobs

- Remove nested child enumeration and authorization from Rum render paths.
- Make `explorer.cljs` render child-group shells from child-section request
  state, schema metadata, and existing UI expansion state only.
- When a child section expands or pages, schedule a child-section job for the
  current cursor instead of deriving totals inline.
- Each child-section job should:
  collect anchored relationships incrementally,
  dedupe resources,
  run authorization checks outside render,
  accumulate the current page and exact total,
  publish guarded loading/ready/error snapshots, and
  discard full authorized inventories once the page result has been published.
- When a child-section job finishes, automatically enqueue nested jobs for any
  child resources on the visible page that are already marked expanded.
- Keep top-level resource groups on the existing synchronous
  `lookup-resources` path.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 4: Rebuild the user-facing shell on top of the new foundations

- Change the ready subtitle to `EACL Explorer backed by a client-side
  DataScript. Also supports Datomic Pro & Datomic Cloud.`
- Turn the seed controls into a submit form so Enter in the seed input triggers
  `seed-db!`.
- After successful interactive seeding, reset the known-user directory to page
  1.
- Replace lexical known-user ordering with role-aware ordering:
  `super-user`, quick subjects, owners, shared admins, leaders, then lexical
  fallback.
- Remove the separate header `Show Schema` button.
- Always render the schema shell below the header with a compact collapsed
  layout and a caret toggle.
- Move schema write controls and errors to the bottom of the schema editor pane.
- Keep `Write Schema` disabled when the draft is clean or a write is in
  progress.
- Change the schema help copy to `Edit the Spice schema and click Write Schema`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 5: Verify the redesign across integration points

- Add cljs tests for:
  shared runtime persistence boundaries,
  schema shell collapse defaults,
  seed-on-Enter,
  role-aware known-user ordering,
  child-section request-state loading and invalidation, and
  nested page reruns for live section queries.
- Add integration coverage for `eacl/read-relationships` limit/cursor behavior
  through the local DataScript client.
- Run the browser cljs test suites via nREPL.
- Manually verify the browser app for:
  hot-reload state retention,
  schema shell behavior,
  seeding UX,
  known-user visibility after seeding, and
  non-blocking nested resource expansion.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.
