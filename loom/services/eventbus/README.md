# Loom - Eventbus Service

This module is a **placeholder** for future Vert.x EventBus integration code.

## Current State

The module has no source files. All actual event dispatching in MetaLoom currently lives in:

- **Cortex Pipeline Event Bus** (`cortex/pipeline-common`): in-process pub/sub via `DefaultPipelineEventBus`
- **WebSocket fan-out** (`loom/services/rest`): `PipelineEventBroadcaster` + `PipelineEventEndpoint` + `ProcessorEndpoint`
- **MCP tool dispatch** (`loom/services/mcp`): `MCPToolRegistry` uses `vertx.eventBus()` for tool invocation at `mcp.tool.<name>`

See `spec/cortex/EVENTBUS.md` for the full architecture, progress, and TODO list.
 