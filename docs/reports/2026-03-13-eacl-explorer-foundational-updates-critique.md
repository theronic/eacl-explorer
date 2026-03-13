# Report: Critique of the Initial Explorer Update Plan

Plan under review:
[2026-03-13-eacl-explorer-foundational-updates.md](../plans/2026-03-13-eacl-explorer-foundational-updates.md)

## Summary

The initial plan identifies the right hot spots, but it still leaves too much
room for tactical fixes layered on top of the current render-time derivation
model. If these requirements had been foundational from the start, the explorer
would have treated nested section data as runtime-managed query state, not as a
pure render helper that occasionally needs to be deferred.

The upgraded plan should therefore treat three concerns as first-class
foundations:

- a persistent browser runtime distinct from test runtimes,
- explicit asynchronous child-section query state, and
- indexed relationship enumeration in `eacl-datascript`.

## Findings

### 1. The initial plan improves runtime persistence, but it does not yet make the shared runtime the canonical browser boundary

The user explicitly asked for `(defonce conn ...)` so the in-memory DataScript
DB survives hot reloads. The initial plan includes that, but it still talks
about runtime reuse mostly as an implementation detail in `state.cljs`.

If the app had been designed around hot reload from day one, the boundary would
have lived in the seed/runtime layer:

- the app would use one shared browser runtime,
- tests would use fresh runtimes, and
- state initialization would consume whichever runtime contract it was given.

Recommendation:

- Put the shared `defonce conn` and `defonce client` in `seed.cljs`, not
  `state.cljs`.
- Make `shared-runtime` the canonical app entrypoint and keep `create-runtime`
  as the isolated constructor.

### 2. The initial plan treats the schema-shell changes as UI tweaks rather than a state-model cleanup

Removing the `Show Schema` button is not just a cosmetic change. It exposes that
the current `:show-schema?` flag is modeled around mount/unmount rather than
around expansion state for an always-present tool panel.

If the schema shell had been foundational, it would always exist in the layout
and its state would only control compact versus expanded presentation.

Recommendation:

- Replace `:show-schema?` with `:schema-expanded?`.
- Always render the shell and let the state control only its collapsed or
  expanded geometry.
- Keep write controls at the bottom so the editor reads top-to-bottom like a
  real editing surface.

### 3. The user visibility issue needs a product-level redesign, not just a technical refresh

The investigation showed that seeded users are present, but the left column is
sorted lexically, which pushes many newly created identities behind `leader-*`
entries. Resetting to page 1 after seeding helps, but it does not solve the
underlying discoverability problem.

If subject browsing had been foundational, the directory would have been ordered
around human relevance, not raw id sort.

Recommendation:

- Introduce role-aware user ordering:
  `super-user`, quick subjects, owners, shared admins, leaders, then lexical
  fallback.
- Keep pagination, but make the first page meaningful for seeded exploration.
- Reset the directory page to page 1 after interactive seeding so new results
  become visible immediately.

### 4. Promises alone are the wrong abstraction for the nested-query lag

The current slow path is CPU-bound work on the browser thread:

- `read-relationships` scans too broadly,
- child resources are deduped and sorted synchronously,
- `can?` is run over every child before the UI paints, and
- this happens during Rum render.

Wrapping that work in a promise would not make it non-blocking if the same work
still runs synchronously inside the promise callback on the main thread.

If nested sections had been foundational, they would have been modeled as async
runtime-managed query jobs with progressive publication and stale-result guards.

Recommendation:

- Move all nested child enumeration and authorization out of render.
- Keep child-section state focused on the live request: current cursor, current
  page, total, status, and error.
- Run child-section work cooperatively with `js/setTimeout` chunking.
- Treat render as a consumer of live query state only.

### 5. The initial plan needs a stronger pagination model, but full result caching is the wrong foundation here

The current nested pagination model stores opaque navigation state but
recomputes the authorized child set each time. That part is worth fixing. The
mistake in the initial plan is assuming that the clean solution is to cache full
authorized child inventories in app state.

If the system had assumed fast anchored reads from the start, pagination would
have been modeled as a live query over one indexed relationship path, with only
the current page state retained in memory.

Recommendation:

- Do not cache full authorized child result sets in app state.
- Make section jobs rerun against the indexed relationship path for each cursor.
- Retain only current-page request state and invalidate it on
  `[db-rev subject permission]`.

### 6. The underlying `eacl-datascript` relationship read path is a foundational defect and should be fixed before polishing the explorer job model

The measured slowdown is partly an explorer problem, but the dependency makes it
worse: `impl/read-relationships` currently walks all relationship datoms and
filters them in memory. That violates the intent of the tuple indexes already
present in the schema.

If `eacl-datascript` had been designed for explorer-style anchored traversal
from the start, anchored relationship reads would have been index-backed and
cursorable.

Recommendation:

- Prioritize the `eacl-datascript` indexed read fix before building the final
  async child-section loader.
- Normalize cursor ids in `datascript-read-relationships`.
- Use forward and reverse tuple indexes when the query is anchored enough to do
  so, with the old scan as fallback only.

### 7. The phase order should move from foundations to user-facing behavior to verification

The initial plan is close, but the phase boundaries can be cleaner.

If this feature set had been foundational, implementation order would have been:

1. fix the shared runtime and state model,
2. fix the dependency path that makes anchored reads expensive,
3. build the async child-section runtime model on top of that,
4. reshape the UI around the new state model, and
5. verify behavior and regressions.

Recommendation:

- Move the `eacl-datascript` index work earlier.
- Treat the async child-section runtime as the main architectural change, then let
  the schema/header UX changes sit on top of that state model.

## Upgraded Recommendations

- Promote `seed.cljs` to the canonical runtime boundary with shared and isolated
  runtime constructors.
- Replace the schema visibility toggle with an always-mounted collapsible shell.
- Reorder known users by role relevance and reset pagination after seeding.
- Introduce live async child-section jobs as the explorer’s foundational model
  for nested data.
- Fix indexed anchored relationship reads in `eacl-datascript` before finalizing
  the child-job execution path.
- Reorder the implementation phases so runtime and data access precede UI polish
  and verification.
