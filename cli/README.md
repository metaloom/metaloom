# MetaLoom CLI

`metaloom` — a command line client for a Loom server. Built with PicoCLI + Dagger 2, and
shipped either as a runnable JAR or as a GraalVM native image.

User-facing documentation lives in
[website/content/english/docs/cli](../website/content/english/docs/cli/index.adoc).
The design notes are in [spec/features/cli/CLI_PLAN.md](../spec/features/cli/CLI_PLAN.md).

## Build

```bash
mvn -pl cli -am package             # JAR: target/metaloom-cli.jar
./build-native.sh                   # native binary: target/metaloom
./build-native.sh smoke             # smoke-test the binary
./build-native.sh install           # copy to ~/.local/bin/metaloom
```

The native build needs GraalVM 25; set `GRAALVM_HOME` if it is not at `/opt/jvm/graalvm-25`.

## Layout

```
MetaLoomCLIMain      entry point — one parse, no bootstrap pass
MetaLoomCLI          root command; global options as INHERIT-scoped setters
CliContext           the resolved settings for one invocation
cmd/                 command classes, grouped by area
client/              LoomApi port + REST impl, error mapping, event stream
config/              cli.yml, credentials.yml, server URL parsing
output/              table / json / yaml renderers, colour
dagger/              component, modules, IFactory, execution strategy
```

## Two things worth knowing before changing it

**Adding a command needs two edits.** The `@Command(subcommands = …)` annotation *and* an
`@IntoMap` binding in `CommandModule`. Without the binding, `DaggerCliFactory` silently falls
back to picocli's reflective factory and produces a command whose injected fields are null —
it compiles, and fails only when someone runs it. `CliCommandTreeTest` catches this.

**Configuration is resolved between parsing and executing.** `CliExecutionStrategy` fills in
everything the command line did not supply, using `ParseResult.hasMatchedOption` to tell an
explicit flag from a default. That is why the client is behind a `Provider` and why, unlike
`cortex/cli`, there is no throwaway first parse.

## Tests

```bash
mvn test -pl cli                                            # unit
mvn test -pl integration-test -Dtest=CliIntegrationTest     # against a real Loom
./build-native.sh smoke                                     # against the native binary
```

The integration test needs the test database pool (`./setup-pool.sh` from the repo root).
