# MetaLoom // Cortex — Custom Instance (Daemon) Example

This example shows how to assemble a **custom Cortex instance** — a long-running daemon that includes
your own node and connects to a Loom backend.

It is a stripped-down fork of the real Cortex CLI: the picocli command layer is gone. There is just a
`main` that builds a `Cortex` instance and runs it in the foreground until the process is stopped —
exactly the shape you want for a container (PID 1) supervised by Kubernetes, Docker, or systemd.

## What it does

[`CortexCustomMain`](./src/main/java/io/metaloom/cortex/cli/CortexCustomMain.java):

```java
public static void main(String... args) {
    Cortex cortex = buildComponent(null).cortex();
    Runtime.getRuntime().addShutdownHook(new Thread(cortex::shutdown, "cortex-shutdown"));
    cortex.run(); // blocks until shut down
}
```

`buildComponent(null)` assembles the Dagger object graph
([`CortexComponent`](./src/main/java/io/metaloom/cortex/cli/dagger/CortexComponent.java)); passing
`null` options makes the graph resolve its configuration from the environment via the
`CortexOptionsLoader`. `cortex.run()` starts the monitoring endpoint, opens the control channel to
Loom, registers the worker, and blocks.

## Including the custom node

The daemon's node set is defined in two small modules you own and edit:

- [`NodeCollectionModule`](./src/main/java/io/metaloom/cortex/cli/dagger/NodeCollectionModule.java) —
  includes the built-in Cortex node modules **plus** `HelloWorldNodeModule` from the
  [`cortex-custom-node`](../cortex-custom-node) example.
- [`PipelineNodeFactoryModule`](./src/main/java/io/metaloom/cortex/cli/dagger/PipelineNodeFactoryModule.java) —
  registers the `hello-world` node type with the pipeline node factory:

  ```java
  factory.register("hello-world", def -> adapt(helloWorld, def, cortexOptions));
  ```

To add your own node: depend on its module, `include` it in `NodeCollectionModule`, and `register`
its type in `PipelineNodeFactoryModule`. That is the whole extension surface.

## Running the daemon

Build a runnable jar and start it, pointing it at your Loom backend:

```bash
mvn -pl examples/cortex-custom -am package

LOOM_HOST=localhost \
LOOM_PORT=8092 \
CORTEX_MONITORING_PORT=8093 \
java -jar examples/cortex-custom/target/cortex-custom-*.jar
```

### Configuration (environment variables)

| Variable | Purpose | Default |
|---|---|---|
| `LOOM_HOST` | Loom backend host to register with | `localhost` |
| `LOOM_PORT` | Loom backend port | `8092` |
| `CORTEX_MONITORING_PORT` | health / readiness HTTP port | `8093` |
| `CORTEX_META_PATH` | local sidecar metadata cache path | `~/.cache/metaloom/cortex/meta` |
| `CORTEX_NODE_ID` | stable worker id | generated |

### Health & readiness

The daemon exposes a small monitoring HTTP server (default port `8093`):

| Endpoint | Meaning |
|---|---|
| `GET /api/health` | liveness — always `200` while the process is up |
| `GET /api/ready` | readiness — `200` once connected **and** registered with Loom, else `503` |

These map cleanly onto Kubernetes `livenessProbe` / `readinessProbe`.

When Loom hands the worker media to process, the `hello-world` node persists its result into the
`asset_json_comp` table via REST — see the [`cortex-custom-node`](../cortex-custom-node) README for
how that works.

## Testing the assembly

[`InstanceTest`](./src/test/java/io/metaloom/cortex/cli/InstanceTest.java) builds the component and
verifies the `hello-world` node is wired, without starting the blocking daemon or needing a running
Loom backend:

```bash
mvn -pl examples/cortex-custom test
```

## License

Apache License, Version 2.0.
