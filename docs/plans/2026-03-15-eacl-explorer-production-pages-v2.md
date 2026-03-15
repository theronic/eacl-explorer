# Plan v2: TDD-First Production Artifact Repair and GitHub Pages Delivery

## Summary

This version upgrades the original plan by treating production compilation,
path-relative static hosting, and CI-built deployment artifacts as foundational
constraints rather than follow-up polish.

The design assumptions are:

- explorer query helpers publish canonical UI identifiers,
- Rum render code never depends on raw `name` over untrusted values,
- deployable artifacts live in `dist/` and nowhere else, and
- the repo builds reproducibly from pinned Git dependencies in local and CI
  environments.

## Architectural Rules

- `explorer.cljs` owns normalization of resource-type and permission identifiers
  before they reach UI consumers.
- `core.cljs` renders through presentation helpers, not direct `name` calls on
  dynamic values.
- `resources/public` is for checked-in static assets and watch-mode outputs
  only.
- `dist/` is the only deploy artifact root and must be fully reproducible from
  source.
- Every implementation phase begins with the tests or checks that fail before
  the change and pass after it.

## Handoff Checkpoint

- [ ] Stop after writing this upgraded plan so Codex can be restarted and
  `chrome-mcp` can be repaired for the implementation agent.
- [ ] Resume implementation starting from Phase 1 only after the new agent has
  a working browser verification path.

## Phases

### Phase 1: Lock down the production failure with tests and diagnostics

- Add cljs tests that model non-canonical permission and resource-type inputs
  and assert that explorer query helpers normalize them into canonical keyword
  data for the UI.
- Add cljs tests that exercise the new presentation helpers on nil, string, and
  keyword values so render paths cannot crash on transitional data.
- Reproduce the current optimized build failure with `shadow/release --debug`
  and confirm the failing lines match the new test coverage.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 2: Implement the canonical identifier boundary

- Add normalization helpers in `explorer.cljs` for resource types and
  permission names.
- Apply those helpers to DB-backed and schema-backed query paths so
  `query-resource-types`, `permissions-by-type`, and `selectable-permissions`
  return canonical values.
- Add safe presentation helpers in `core.cljs` and replace render-time `name`
  usage in visible labels, ids, and keys.
- Re-run the browser cljs tests immediately after the change.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 3: Create a deploy-grade artifact boundary

- Add a dedicated `:pages` browser build in `shadow-cljs.edn` that writes only
  to `dist/js`.
- Update checked-in HTML entrypoints to use relative asset URLs.
- Add a build script that wipes `dist/`, runs the `:pages` release build, copies
  static assets, and writes `.nojekyll`.
- Add `/dist` to `.gitignore`.
- Build the optimized artifact immediately and confirm the local static output
  renders without console errors.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 4: Replace local-only dependencies with reproducible Git dependencies

- Update `deps.edn` to replace local-root EACL dependencies with pinned Git
  dependencies using `eacl/*` coordinates and HTTPS Git URLs.
- Pin both modules to the same Git SHA so the app and adapter are built from
  one coherent upstream revision.
- Rebuild from the pinned basis and confirm the project still compiles and the
  browser tests still pass.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 5: Add GitHub Pages CI as a direct consequence of the artifact model

- Add a GitHub Actions workflow that checks out the repo, installs Clojure and
  Node, restores Maven/gitlibs caches, runs the Pages build script, uploads the
  `dist/` artifact, and deploys with the official Pages actions.
- Target GitHub Actions as the publishing source rather than branch-root
  publishing.
- Keep the workflow aligned with the local build script so CI and local output
  stay identical.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 6: Prove the finished system locally in the same shape Pages will serve

- Re-run the browser cljs tests from the watched test build.
- Serve `dist/` locally under a prefixed URL such as `/eacl-explorer/`.
- Verify via Chrome MCP that the optimized app shell renders, static assets load
  from the prefixed path, and the console is clean.
- Confirm that the local production preview is the same artifact the workflow
  will publish.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.
