# EACL v8 DataScript query benchmark

Measured on 2026-08-02 in Chromium with freshly seeded Explorer runtimes.

## Method

- Explorer baseline: `origin/main` at `977030d`, using EACL v7 at
  `457b137bad63ae248728885d611421f3227aa75c`.
- Pre-PR-#95 runtime: EACL v8 at
  `7df4d4be4f014786662197248f8c5ddbef17ab65`.
- PR-#95 runtime: EACL v8 at
  `bb69a6bd17252bad6d9f2aacdd65a70cb9832c50`.
- PR-#96 runtime: EACL v8 at
  `73c5488ce35f949f471670f115b5fdccea4b1ec2`.
- Final PR-#97 runtime: EACL v8 at
  `599bdd177b42368ac0d17213f50eb53b292c037c`.
- Dataset: 5 accounts, 20 teams, 10 VPCs, 10,000 servers, and 38 users.
- Each workload receives 20 warm-up calls followed by 30 measured samples.
- Fast workloads are batched within each sample to improve timer resolution.
- Seeding is excluded from every query measurement.
- The pre-PR-#95 run uses
  `{:cache cache/no-cache :proof-mode :none}`.
- PR #95 is measured both with its default client-private exact-current cache
  (the Explorer's production configuration) and with the same explicit
  `no-cache + proof-mode :none` configuration as the pre-PR-#95 run.
- PR #96 uses the same default client-private exact-current cache configuration
  as PR #95.
- Cached counts became too fast for one invocation to resolve reliably against
  the browser's 0.1 ms timer, so the PR-#95 harness batches 50 count invocations
  per sample. All reported values remain per invocation.
- Relationship insert and delete costs are intentionally excluded.

The v7 and v8 page sizes are identical. Only their public pagination syntax
differs: v7 uses `:limit`; v8 uses Relay `:first`.

## Relay cache-hit overhead fix

The first PR-#97 revision,
`34286ca5b3aaa0c4dc9277d600b6efe110d69f1a`, made an authenticated
continuation cacheable when cursor selection retained the current immutable
snapshot. That removed recursive engine execution on a hit, but did not remove
the dominant browser cost.

Profiling showed that EACL still authenticated a continuation twice, rebuilt
current and cursor snapshot proofs twice, rebuilt the same page proof for both
boundary cursors, and re-canonicalized and authenticated both output cursors on
every hit. The cache lookup itself took about 0.07 ms; this surrounding
cursor-proof work accounted for almost all of the remaining 8–17 ms.

Commit `599bdd177b42368ac0d17213f50eb53b292c037c` now:

- authenticates and internalizes each continuation once;
- builds one immutable proof context for both output cursors;
- memoizes non-expiring client-minted cursor codecs inside EACL;
- learns a snapshot-scoped opposite-direction alias when adjacent pages are
  visited, so the first Back/Forward traversal reuses the page already computed.

The Explorer stores only the Relay request and page number. It does not cache
query results. Next sends `first/after`, Prev sends `last/before`, and First
resets to a cursor-free `first` request.

The direct EACL comparison uses a freshly seeded 10,000-server runtime,
`super-user`, `:view`, page size 20. Post-fix distributions use 50 samples of
10 invocations:

| Path | Before final fix | After final fix | Reduction |
| --- | ---: | ---: | ---: |
| Warm first page, mean | 9.059 ms | 0.638 ms | 93.0% |
| Warm continuation, mean | 16.864 ms | 0.905 ms | 94.6% |

The post-fix p50/p95 values are 0.590/0.970 ms for the first page,
0.900/1.000 ms for the continuation, and 0.830/0.970 ms for the reverse alias.

Through the real development UI, a cold first page took 10.7 ms, the first Back
to a visited page took 2.8 ms, and Forward to that visited page took 2.9 ms.
Before the alias fix, the first Back took 21.1 ms because the equivalent
`last/before` request had a different semantic cache key.

For context, one observed hosted-v7 first page took 15.1 ms. Count remained
comparable at 82.8 ms in the final local v8 runtime and 86.3 ms in hosted v7;
count does not perform Relay cursor work.

### Why formal verification did not detect the regression

The verification boundary proves the values of selected pure decisions. It
does not prove latency or a bound on duplicated work:

- performance is explicitly excluded by
  `formal/verification/trusted-boundary.md`;
- crypto and canonicalization are treated as trusted-boundary axioms;
- `formal/verification/final-assurance-audit.md` records that the whole public
  engine is not generated-authoritative, and Explorer uses the default
  `:legacy-authoritative` mode;
- the pagination performance gate models primarily Datomic traversal with the
  completed cache disabled;
- the cache performance case measures `can?`, not DataScript/CLJS Relay lookup;
- normal CI checks only that the performance-gate EDN is numerically
  well-formed, while benchmark-tagged tests are excluded.

Recomputing a proof and reusing it have the same semantic output, so the
existing theorem cannot distinguish them. PR #97 adds deterministic
normal-suite checks for the missing work invariants: one cursor authentication,
one page proof-context build, one encode per unique client-owned cursor, and an
immediate reverse-page cache hit.

## PR #96 upgrade verification

Times are p50 microseconds per invocation from a fresh PR #96 runtime. The
comparison uses the earlier PR #95 run on the same machine and browser.

| Workload | PR #95 default cache | PR #96 default cache | Change |
| --- | ---: | ---: | ---: |
| Direct authorization allow | 34 | 34 | unchanged |
| Recursive authorization allow | 30 | 32 | 6.7% slower |
| Recursive authorization deny | 28 | 30 | 7.1% slower |
| Lookup visible server page (20) | 3,540 | 4,500 | 27.1% slower |
| Count 4,000 visible servers | 26 | 24 | 7.7% faster |
| Read account-to-server page (20 of 2,000 matches) | 17,640 | 19,200 | 8.8% slower |
| Read known-user relationship page (20) | 2,680 | 3,340 | 24.6% slower |

Two additional PR #96 runs produced 4.46–4.82 ms for the lookup page,
18.40–18.93 ms for the broad relationship page, and 3.40–3.41 ms for the
known-user relationship page. The paginated-query regression is therefore
repeatable in this browser session, not a single outlier. This benchmark does
not isolate its cause; PR #96's stricter secure-format and cursor validation is
a candidate that requires profiling before attribution.

The fresh PR #96 run recorded 6,245 exact-current hits, 5 misses, and 5 cache
puts, matching PR #95. It also recorded zero managed-current hits, bypasses,
stamp failures, and admission entries. The cache-hit behavior is therefore
unchanged for the Explorer's default configuration.

PR #96 does not improve the Explorer query benchmark overall. It preserves the
very fast authorization and completed-count cache hits, while paying measurable
additional cost on paginated lookup and relationship reads.

## PR #95 results

Times are p50 microseconds per invocation. “Faster” compares PR #95 with the
pre-PR-#95 v8 measurement.

| Workload | v7 | Pre-PR-#95 v8 | PR #95 default cache | Change |
| --- | ---: | ---: | ---: | ---: |
| Direct authorization allow | 34 | 466 | 34 | 13.71× faster |
| Recursive authorization allow | 88 | 888 | 30 | 29.60× faster |
| Recursive authorization deny | 352 | 1,128 | 28 | 40.29× faster |
| Lookup visible server page (20) | 1,040 | 5,480 | 3,540 | 1.55× faster |
| Count 4,000 visible servers | 11,800 | 25,800 | 26 | 992.31× faster |
| Read account-to-server page (20 of 2,000 matches) | 170 | 88,840 | 17,640 | 5.04× faster |
| Read known-user relationship page (20) | 240 | 3,180 | 2,680 | 1.19× faster |

The cached count result is a completed-answer hit, not a faster full graph
count. The uncached result below remains approximately 25.7 ms.

The default-cache run recorded 6,245 exact-current hits, 5 misses, and 5 cache
puts. No managed-current entries were used. The exact tier is therefore enough
to produce these results without assuming that every DataScript write goes
through EACL.

### Cache-free comparison

This table isolates PR #95's cache-free control path by holding the explicit
client configuration equal to the pre-PR-#95 run:

| Workload | Pre-PR-#95 v8 | PR #95 no-cache | Change |
| --- | ---: | ---: | ---: |
| Direct authorization allow | 466 | 184 | 2.53× faster |
| Recursive authorization allow | 888 | 264 | 3.36× faster |
| Recursive authorization deny | 1,128 | 632 | 1.78× faster |
| Lookup visible server page (20) | 5,480 | 6,000 | 9.5% slower |
| Count 4,000 visible servers | 25,800 | 25,696 | effectively unchanged |
| Read account-to-server page (20 of 2,000 matches) | 88,840 | 17,540 | 5.06× faster |
| Read known-user relationship page (20) | 3,180 | 2,600 | 1.22× faster |

PR #95 removes most of the cache-control overhead from `can?`, but it does not
restore the uncached v7 query costs. Its largest wins come from completed-answer
reuse and removing the relationship cursor's complete-result digest.

### Correctness checks

- Both Explorer browser builds compile with zero warnings.
- The four Explorer browser test namespaces pass: 37 tests, 179 assertions,
  zero failures and zero errors.
- The same compile and test results hold after upgrading to PR #96.
- Every benchmark sample validates its semantic result: the expected allow or
  deny, a 20-item page, or exactly 4,000 visible servers.
- Cached and explicit no-cache clients run against the same seeded DataScript
  database.

## Original pre-PR-#95 results

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

No workload in this original run is faster on v8. The smallest median
regression is the full visible-server count at 2.19×. The dominant hotspot is
relationship pagination over a broad match set.

## Relationship cursor findings

DataScript v8 still materializes and canonically sorts every matching
relationship before applying the requested Relay window. The cursor then
commits to the selected exact snapshot and an offset. A page of 20 from 2,000
matches therefore remains an `O(matches)` operation even though the cursor no
longer hashes the complete ordered match set. Repeating that read for every page
makes a complete fixed-size traversal `O(matches × page-count)`.

The benchmark initially found that EACL also generated the same full-set proof
independently for both boundary cursors. EACL commit
`7df4d4be4f014786662197248f8c5ddbef17ab65` reuses one proof per read and adds
a regression assertion. That reduced the 2,000-match workload's median from
159.68 ms to 84.13 ms (47.3%) and the small relationship workload from 4.67 ms
to 3.00 ms (35.8%). It removes duplicate work, but it does not eliminate the
remaining full-result proof and materialization cost.

PR #95 replaces the complete-result cursor proof with an authenticated exact
snapshot proof. That reduces the 2,000-match page from 88.84 ms to 17.64 ms, but
full relationship materialization and canonical sorting remain. The nearly
identical cached and no-cache relationship timings confirm that this residual
cost is outside the completed-answer cache.

The opt-in harness is `test/eacl/bench/query_benchmark.cljs`; it is compiled
with the browser test build but is not part of the regular test suite.
