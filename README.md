# EACL Explorer

Browser-only EACL explorer using:

- Rum for rendering
- DataScript for local storage and querying
- EACL v8 + `eacl-datascript` for authorization
- `shadow-cljs` for the browser build

The explorer boots with the default Spice schema and root subjects in-browser at
`http://localhost:18091/index.html`.
Use the `Seed DB` control in the header to append benchmark-shaped data into the
live DataScript database. The default seed size is `10,000` servers.

The v8 integration uses Relay-style `:first`/`:after` pagination and consumes
opaque cursors exclusively through the returned `:page-info`.

## Development

Start an nREPL with the dev alias:

```bash
clojure -M:dev:nrepl
```

Discover the port if needed:

```bash
clj-nrepl-eval --discover-ports
```

Start both browser builds from nREPL:

```clojure
(do
  (require '[shadow.cljs.devtools.server :as server]
           '[shadow.cljs.devtools.api :as shadow]
           :reload)
  (server/start!)
  (shadow/watch :app)
  (shadow/watch :test))
```

Open:

- `http://localhost:18091/index.html` for the explorer
- `http://localhost:18091/test/index.html` for the browser test build

## CLJS REPL workflow

Select the app build:

```clojure
(do
  (require '[shadow.cljs.devtools.api :as shadow] :reload)
  (shadow/nrepl-select :app))
```

Select the test build:

```clojure
(do
  (require '[shadow.cljs.devtools.api :as shadow] :reload)
  (shadow/nrepl-select :test))
```

The nREPL session persists, so once the browser REPL is selected you can
evaluate ClojureScript directly through `clj-nrepl-eval`.

## Running tests

After `http://localhost:18091/test/index.html` is open and the test REPL is selected:

```clojure
(require 'eacl.explorer.seed-test :reload)
(require 'eacl.explorer.explorer-test :reload)
(require 'eacl.explorer.state-test :reload)
(cljs.test/run-tests
  'eacl.explorer.seed-test
  'eacl.explorer.explorer-test
  'eacl.explorer.state-test)
```

## Query benchmark

The test build includes a browser-side query microbenchmark without executing
it as part of the test suite. Seed and start one workload from the selected
test REPL:

```clojure
(require '[eacl.bench.query-benchmark :as bench] :reload)
(def runtime (bench/prepare-runtime!))
(bench/start-benchmark-workload!
  (:client runtime)
  (fn [page-size] {:first page-size})
  :can-recursive-allow)
```

The asynchronous entry point yields between samples so a large graph does not
starve the browser connection. Poll `(bench/benchmark-status)` until its state
is `:done` or `:failed`.

See [the EACL v7/v8 query comparison](docs/eacl-v8-query-benchmark.md) for the
10,000-server baseline and findings.

## Seeding

- the app starts with no seeded accounts, teams, VPCs, or servers
- `Seed DB` appends more data without resetting the current database
- the default header value seeds `10,000` servers
- cljs tests still use the `:smoke` and `:benchmark` seed profiles directly

## Project layout

- `src/eacl/explorer/seed.cljs`
  dataset topology, schema, and DataScript batch execution
- `src/eacl/explorer/explorer.cljs`
  pure explorer derivation over `(db, acl, ui-state)`
- `src/eacl/explorer/state.cljs`
  runtime bootstrap, invalidation, and deferred count jobs
- `src/eacl/explorer/core.cljs`
  Rum UI
- `test/eacl/explorer/`
  browser-side cljs tests
- `test/eacl/bench/`
  opt-in browser-side EACL query benchmarks
