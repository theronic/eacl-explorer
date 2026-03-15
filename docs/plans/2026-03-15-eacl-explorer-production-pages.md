# Plan: Production Build Repair and GitHub Pages Delivery for EACL Explorer

## Summary

`eacl-explorer` already runs as a client-only Rum + DataScript demo in watch
mode, but the optimized production artifact does not render and the current
asset/dependency setup is not suitable for GitHub Pages or GitHub Actions.

This plan fixes the production compilation path first, then makes the app
deployable as a static artifact built from source in CI.

## Foundational Direction

- Production and development should be two views of the same application, not
  separate code paths with separate assumptions.
- Static assets should be path-relative so the app works at `/` and at
  `/eacl-explorer/` without special-case HTML.
- CI should build from source only; generated JS should remain untracked.
- Dependency resolution should be Git-backed and reproducible, not tied to
  local worktree paths.

## Phases

### Phase 1: Reproduce and isolate the production-only failure

- Confirm that `shadow/release` fails while watch mode succeeds.
- Use release source maps to identify the exact UI render paths that break in
  production.
- Trace the resource-type and permission values that feed those render paths so
  the fix lands at the correct boundary instead of masking symptoms.
- Add failing tests that model the invalid production inputs before changing
  implementation.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 2: Repair the production-safe data and rendering boundary

- Normalize resource-type and permission data where explorer query helpers
- produce UI-facing values.
- Replace render-time `name` calls on untrusted values with safe display helpers
  so the UI cannot crash on malformed or transitional data.
- Keep the visual output unchanged for valid keyword inputs.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 3: Make the static site path-agnostic

- Change app and test HTML to use relative asset URLs.
- Add a dedicated production Pages build that writes into `dist/`.
- Ensure the same artifact works when served from a project subpath such as
  `/eacl-explorer/`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 4: Replace local-only dependencies with Git-backed dependencies

- Update `deps.edn` so `eacl` and `eacl-datascript` resolve from GitHub via
  pinned SHAs.
- Change local `cloudafrica/*` coordinates in this repo to `eacl/*`.
- Verify that the project still compiles from a clean dependency basis.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 5: Add the production build and deployment pipeline

- Add a checked-in build script that creates the final `dist/` artifact.
- Add a GitHub Actions workflow that installs dependencies, builds the Pages
  artifact, uploads it, and deploys via GitHub Pages.
- Switch the repo’s expected Pages source from branch publishing to GitHub
  Actions.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 6: Verify locally before GitHub Pages

- Run the browser cljs tests.
- Build the optimized production artifact.
- Serve the artifact locally from a prefixed URL and confirm it renders without
  console errors.
- Verify that the core shell, controls, and bundled JS all load correctly via
  Chrome MCP.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.
