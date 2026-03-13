# Report: Critique of the Initial Rum Rewrite Plan

Plan under review: [2026-03-13-eacl-explorer-rum-rewrite.md](../plans/2026-03-13-eacl-explorer-rum-rewrite.md)

## Findings

### 1. The plan still treats the browser runtime as a transport swap instead of a foundational architectural reset

The current system is fundamentally organized around server request handling:
`routes.clj`, `view_state.clj`, and `views.clj` exist to stream HTML fragments
to the browser. A browser-only rewrite should not preserve those seams in spirit
or in module layout. If this had been a client-side application from the start,
the primary boundary would have been:

- pure domain queries,
- pure seed dataset generation,
- client runtime/bootstrap,
- UI state orchestration, and
- Rum rendering.

The initial plan names the right ingredients, but it does not clearly enforce
that decomposition. That leaves too much room for a thin port of the current
server structure into cljs rather than a cleaner browser-native design.

### 2. The seed strategy is not yet cleanly separated into pure dataset generation versus execution

The existing seed namespace mixes three concerns:

- dataset topology,
- backend-specific transaction execution, and
- seed lifecycle policy.

In a foundational client-first design, the topology should be backend-agnostic
and reusable, while the execution layer should be the only place that knows how
to transact into DataScript. Without that split, the rewrite risks producing a
large cljs namespace that is harder to test and harder to evolve when the seed
profile changes again.

### 3. The plan does not give a sufficiently explicit invalidation model for reactive reruns

The goal is not just “run on DataScript”; it is “rerun queries whenever the
DataScript DB changes.” The initial plan mentions a db revision signal, but it
does not make the invalidation contract explicit enough:

- which derived queries rerender synchronously,
- which jobs restart asynchronously,
- when cached schema/permission derivations are invalidated, and
- how bootstrap seeding interacts with normal rerender logic.

If this had been foundational from the start, the app would have had one clear
rule: all derived panel data is a function of `(db, ui-state)`, and only the
expensive top-level counts are deferred jobs keyed by the same inputs.

### 4. The phase ordering still allows obsolete structures to leak into the new build

The initial phase order waits until late in the rewrite to remove the old
server-oriented code. That is backwards for a clean reset. The new build
surface should be established early, and the new source set should become the
only active source set before most feature work continues. Otherwise, the
rewrite can accidentally keep satisfying itself with old codepaths that should
already be dead.

### 5. The validation plan should bias harder toward browser-native cljs logic and thinner dev-only JVM tooling

The existing explorer logic is rich and testable. The best redesign is to keep
as much of the seed and query logic in plain `.cljs` as possible so the codebase
matches the real deployment target. The initial plan points toward shared host
logic, but that is not a requirement here. If this had been designed for a
client-side DataScript browser from the beginning, Rum would still have been
only the rendering layer, but correctness would have lived in pure cljs modules
validated through a browser-backed cljs REPL over nREPL.

## Recommendations

- Recast the rewrite around four foundational layers: dataset spec, query
  derivation, client runtime/state orchestration, and Rum rendering.
- Make the dataset topology pure and backend-neutral, with a small executor that
  turns dataset batches into DataScript transactions and EACL relationship
  writes.
- State the reactive contract explicitly: all panels derive from `(db, ui)`,
  counts are deferred jobs keyed by `[db-rev subject permission]`, and any db
  change invalidates per-revision memoized schema/query support data.
- Promote the new cljs-first source set and build surface before migrating the
  remaining features, so the rewrite cannot accidentally depend on the old
  server pathways.
- Make `.cljs` the default for seed and explorer logic and limit JVM-side code
  to dev helpers that manage the browser build and REPL workflow.

## Required Upgrades To The Plan

- Introduce an explicit “new source set becomes canonical” phase before most
  feature migration.
- Split seeding into pure dataset generation and DataScript execution.
- Define the invalidation model and count-job model in the plan, not just in the
  eventual implementation.
- Tighten the phase order so cleanup happens immediately after the new build
  surface is active, not as a trailing concern.
- State that Rum is a rendering layer over pre-derived data, with correctness
  concentrated in pure cljs namespaces and tested through the browser REPL.
