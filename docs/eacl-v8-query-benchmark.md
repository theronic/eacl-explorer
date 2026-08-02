# EACL v8 DataScript query benchmark

Measured on 2026-08-02 in one Chromium browser process with fresh, independently
seeded Explorer runtimes.

## Method

- Explorer baseline: `origin/main` at `977030d`, using EACL v7 at
  `457b137bad63ae248728885d611421f3227aa75c`.
- Upgraded runtime: this branch, using EACL v8 at
  `7df4d4be4f014786662197248f8c5ddbef17ab65`.
- Dataset: 5 accounts, 20 teams, 10 VPCs, 10,000 servers, and 38 users.
- Each workload receives 20 warm-up calls followed by 30 measured samples.
- Fast workloads are batched within each sample to improve timer resolution.
- Seeding is excluded from every query measurement.
- v8 uses `{:cache cache/no-cache :proof-mode :none}`. This explicitly selects
  the uncached query path and avoids charging each in-process query for secure
  cache-proof construction that the demo does not reuse profitably.
- Relationship insert and delete costs are intentionally excluded.

The v7 and v8 page sizes are identical. Only their public pagination syntax
differs: v7 uses `:limit`; v8 uses Relay `:first`.

## Results

Times are microseconds per invocation. The factor is v8 divided by v7, so a
factor greater than 1 is a regression.

| Workload | v7 p50 | v8 p50 | p50 factor | v7 p95 | v8 p95 | p95 factor |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Direct authorization allow | 34 | 466 | 13.71× | 46 | 796 | 17.30× |
| Recursive authorization allow | 88 | 888 | 10.09× | 92 | 976 | 10.61× |
| Recursive authorization deny | 352 | 1,128 | 3.20× | 372 | 1,168 | 3.14× |
| Lookup visible server page (20) | 1,040 | 5,480 | 5.27× | 1,140 | 6,020 | 5.28× |
| Count 4,000 visible servers | 11,800 | 25,800 | 2.19× | 18,600 | 26,500 | 1.42× |
| Read account-to-server page (20 of 2,000 matches) | 170 | 88,840 | 522.59× | 210 | 91,970 | 437.95× |
| Read known-user relationship page (20) | 240 | 3,180 | 13.25× | 290 | 3,290 | 11.34× |

No measured read workload is faster on v8. The smallest median regression is
the full visible-server count at 2.19×. The dominant hotspot is relationship
pagination over a broad match set.

## Relationship cursor finding

DataScript v8 currently materializes and canonically sorts every matching
relationship before applying the requested Relay window. The cursor then
commits to the complete ordered match set, not just the returned page. A page
of 20 from 2,000 matches therefore remains an `O(matches)` operation. Repeating
that read for every page makes a complete fixed-size traversal
`O(matches × page-count)`.

The benchmark initially found that EACL also generated the same full-set proof
independently for both boundary cursors. EACL commit
`7df4d4be4f014786662197248f8c5ddbef17ab65` reuses one proof per read and adds
a regression assertion. That reduced the 2,000-match workload's median from
159.68 ms to 84.13 ms (47.3%) and the small relationship workload from 4.67 ms
to 3.00 ms (35.8%). It removes duplicate work, but it does not eliminate the
remaining full-result proof and materialization cost.

The opt-in harness is `test/eacl/bench/query_benchmark.cljs`; it is compiled
with the browser test build but is not part of the regular test suite.
