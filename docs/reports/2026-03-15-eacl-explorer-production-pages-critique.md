# Report: Critique of the Initial Production Build and Pages Plan

Plan under review:
[2026-03-15-eacl-explorer-production-pages.md](../plans/2026-03-15-eacl-explorer-production-pages.md)

## Summary

The initial plan identifies the right workstreams, but it is still too close to
an incremental repair mindset. If production compilation, static hosting, and
CI-built artifacts had been foundational assumptions from the start, the system
would have drawn stricter boundaries:

- UI code would never call `name` directly on non-canonical values.
- Explorer query helpers would publish canonical presentation-ready identifiers.
- The repo would distinguish development output from deployable output.
- Build entrypoints would be explicit and reproducible across local use and CI.

The upgraded plan should therefore tighten three things:

1. the TDD sequence,
2. the data-contract boundary between explorer state and UI rendering, and
3. the artifact boundary between dev output and deploy output.

## Findings

### 1. The initial plan does not treat canonical UI identifiers as a first-class contract

The current production failure is surfacing in `core.cljs`, but the underlying
issue is not purely visual. The UI is receiving values that it treats as
keyword-like, then calling `name` directly. That makes the view layer fragile.

If this had been foundational, the explorer layer would have promised one of
two things:

- UI-facing identifiers are always canonical keywords, or
- UI-facing labels are always already normalized strings.

The initial plan acknowledges the need to inspect inputs, but it does not yet
state that the fix should harden the contract itself.

Recommendation:

- Make identifier normalization explicit in `explorer.cljs`.
- Make rendering helpers consume a narrow, safe contract even if upstream data
  regresses later.

### 2. The initial plan mentions tests first, but it is not yet fully TDD-complete

The user explicitly asked that the plan follow TDD. The current Phase 1 says to
add failing tests before implementation, which is correct, but the downstream
phases still read like implementation batches with verification at the end.

If TDD had been foundational from the start, each architectural phase would
begin with the tests or assertions that justify the change.

Recommendation:

- Recast the plan so every phase begins with the failing or expanded tests for
  that phase.
- Split verification into “tests added”, “implementation”, and “acceptance
  proof” for each major subsystem.

### 3. The build artifact boundary needs to be stricter

The initial plan correctly introduces `dist/`, but it still frames this mostly
as a convenience output directory.

If GitHub Pages delivery had been foundational, the repo would have had two
clear output domains from day one:

- watch output for local development under `resources/public`,
- release output for deployment under `dist/`.

That distinction matters because it avoids release builds trampling local watch
artifacts and makes local Pages previews match CI more closely.

Recommendation:

- Add a dedicated `:pages` build id instead of reusing `:app` for release.
- Build only the deployable artifact into `dist/`.
- Keep `resources/public/js` and `resources/public/test/js` strictly as watch
  outputs.

### 4. The dependency redesign should treat reproducibility as the primary concern

The initial plan swaps out `:local/root` dependencies, but it does not yet say
how the build should remain stable if upstream `theronic/eacl` continues to
evolve.

If CI reproducibility had been a foundational assumption, the project would
have treated the Git SHA as part of its application contract, not as an
implementation detail.

Recommendation:

- Pin both EACL dependencies to the same SHA and document why.
- Use HTTPS Git URLs so GitHub Actions does not depend on SSH key setup.
- Verify the project builds from that pinned basis after the change.

### 5. The Pages path model should be universal, not deploy-only

The initial plan says to make the static site path-agnostic, which is direction
ally correct, but the strongest form of the fix is simpler: the HTML should
always use relative asset paths.

If project-path hosting had been assumed from the beginning, the app would not
have used leading-slash URLs in any checked-in HTML at all.

Recommendation:

- Switch all checked-in HTML entrypoints to relative asset URLs.
- Ensure the `shadow-cljs` asset path for the deploy build is also relative.
- Verify the same HTML works at both `/index.html` and
  `/eacl-explorer/index.html`.

### 6. The initial verification phase is too end-weighted

The final verification phase covers the right checks, but it leaves too much
risk until the end. The production bug, dependency swap, and Pages path fix are
all independent enough to deserve intermediate proofs.

Recommendation:

- Verify browser tests immediately after the production-render fix.
- Verify the optimized artifact immediately after the Pages build setup.
- Verify the local prefixed preview immediately after the artifact is produced.

## Upgraded Recommendations

- Treat canonical UI identifiers as an explicit contract owned by
  `explorer.cljs`.
- Use TDD per phase, not only at the beginning of the project.
- Introduce a dedicated `:pages` build and keep deploy output isolated in
  `dist/`.
- Pin Git dependencies via HTTPS and verify the pinned basis locally.
- Make all checked-in HTML path-relative.
- Pull verification forward so each major change proves itself before the next
  one lands.
