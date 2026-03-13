# EACL Explorer

You are an expert Clojure programmer assisting with development of EACL Explorer.

# Agentic Development Rules & Guidelines

Follow the agentic coding [rules](.rules/AGENTS.md) and sub-rules.

## Testing

**IMPORTANT: Always run tests via nREPL, never via `clojure` CLI.**
JVM startup from cold is far too slow. Use a running nREPL instead.

# Clojure Parenthesis Repair

The command `clj-paren-repair` is installed on your path.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file
instead of attempting to fix them yourself. If the tool doesn't work,
report to the user that they need to fix the delimiter error manually.

The tool automatically formats files with cljfmt when it processes them.

## Starting the project

Use this exact sequence:

```
clj-nrepl-eval --discover-ports
```

If no nREPL is running, start one with the `dev` alias loaded:

```
clojure -M:dev:nrepl
```

Start the browser builds through nREPL:

```
clj-nrepl-eval -p <port> "(do (require '[shadow.cljs.devtools.server :as server]
                                       '[shadow.cljs.devtools.api :as shadow]
                                       :reload)
                                  (server/start!)
                                  (shadow/watch :app)
                                  (shadow/watch :test))"
```

Run a single test namespace:
```
clj-nrepl-eval -p <port> "(do (require '[shadow.cljs.devtools.api :as shadow] :reload)
                              (shadow/nrepl-select :test)
                              (require 'eacl.explorer.seed-test :reload)
                              (cljs.test/run-tests 'eacl.explorer.seed-test))"
```

Run the main browser test namespaces:
```
clj-nrepl-eval -p <port> "(do (require '[shadow.cljs.devtools.api :as shadow] :reload)
                              (shadow/nrepl-select :test)
                              (require 'eacl.explorer.seed-test :reload)
                              (require 'eacl.explorer.explorer-test :reload)
                              (require 'eacl.explorer.state-test :reload)
                              (cljs.test/run-tests
                                'eacl.explorer.seed-test
                                'eacl.explorer.explorer-test
                                'eacl.explorer.state-test))"
```

Compile both browser builds:
```
clj-nrepl-eval -p <port> "(do (require '[shadow.cljs.devtools.server :as server]
                                       '[shadow.cljs.devtools.api :as shadow]
                                       :reload)
                                  (server/start!)
                                  (shadow/compile :app)
                                  (shadow/compile :test))"
```

Open the app at `http://localhost:18091/index.html` and the test build at
`http://localhost:18091/test/index.html`.

If you hit `Alias ... already exists` in an nREPL session, run `ns-unalias` on that alias before re-requiring the namespace.

Start nREPL with test paths: `clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.3.0\"}}}' -A:test -M -m nrepl.cmdline --port 0`
