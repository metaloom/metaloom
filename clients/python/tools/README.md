# tools

Maintenance scripts. Neither is part of the package, and nothing in `setup.sh`,
`test.sh` or the Maven build runs them.

## `generate_models.py`

Regenerates `loom_client/models/` from the server's Java model classes in
`loom-shared/rest-model`.

The models have to be generated from Java rather than from the API description,
because that description carries no schemas — 137 paths and exactly one schema
component, with request and response bodies documented by example only. There is
nothing there to derive a typed model from.

```bash
python3 tools/generate_models.py           # rewrite the modules
python3 tools/generate_models.py --check   # fail if they are stale
```

Run it whenever the Java models change, and review the diff — including
`coverage.txt`, which lists every Java field and the Python attribute it became. That
file exists so a rename shows up as a reviewable change rather than a silent one.

The generated modules carry a `DO NOT EDIT` banner. Edit this script instead: a field
it cannot map is reported as `UNMAPPED` and fails the run rather than being dropped, so
adding a new Java type means extending `SCALARS` or `OPAQUE_TYPES` here.

It formats its output with `ruff` when available, so that generation and `./lint.sh`
agree. Without ruff the output is still valid, just not normalised, and `--check` will
report it as stale.

What it is *not*: a Java parser. It is a line scanner, which is enough because the model
classes are uniformly formatted POJOs. It handles commented-out fields, nested enums,
generics, and the primitive-versus-boxed distinction that decides whether a field is
always on the wire.

## `extract_fixtures.py`

Pulls the example request and response bodies out of the generated
`loom/doc/src/main/generated/openapi.json` into `tests/fixtures/openapi_bodies.json`.

Those examples are produced by the server's own example classes and serialised by the
server's own mapper, which makes them the best available evidence that the generated
models agree with it — `tests/test_models.py` round-trips every one, and no server has
to be running.

```bash
python3 tools/extract_fixtures.py
```

Re-run after regenerating the API description:

```bash
cd ../../loom/doc && mvn -q exec:java -Dexec.mainClass=io.metaloom.loom.doc.ExampleGenerator
```
