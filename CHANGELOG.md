# 1.0.0-SNAPSHOT

* **Breaking:** the only supported LLM protocol is now the OpenAI chat-completions API. The Ollama
  provider is gone, together with the provider-selection concept it existed for. An Ollama backend
  still works — point the configuration at its `/v1` endpoint instead of its native port.
  * `LOOM_AI_PROVIDER_TYPE` and the chart's `ai.providerType` are **removed**. `LOOM_AI_URL` now
    defaults to `http://127.0.0.1:8080/v1` and `LOOM_AI_MODEL_ID` to `openai/gpt-oss-20b`.
  * The `llm`, `translate` and `filter` nodes take **`openaiUrl`** instead of `ollamaUrl`, and their
    `providerType` option is removed. Pipeline definitions using the old keys must be updated;
    the old key is ignored rather than translated.
  * Default model ids are now their canonical (registry-independent) names — e.g.
    `google/gemma-2-27b-it` for `translate` and `llm`. The applicable model licenses are unchanged.
  * `cortex_ai_calls_total` records the `llm` node under `provider="llm"`, not `provider="ollama"`.
* Token-level streaming with tool calls now works on every supported backend.
  `LOOM_AI_STREAMING=true` previously failed the run terminally on anything but Ollama; the OpenAI
  provider now reassembles streamed `tool_calls` fragments itself.
* Cortex no longer has a command line. It is a container: the image entry point takes no arguments
  and the `server start` / `process run` subcommands, along with every `--flag`, are gone.
  Configuration comes from `cortex.yml` and the same `LOOM_*` / `CORTEX_*` environment variables as
  before — a deployment that already configured the worker through the environment is unaffected.
  As a side effect `cortex.yml` now actually reaches the running worker, which it never did on the
  old CLI path. A missing `CORTEX_NODE_ID` exits with code 2 instead of a picocli usage error.
* Added the `gdrive-source` and `onedrive-source` pipeline node kinds: differential ingest from
  Google Drive, OneDrive and SharePoint document libraries. Both use the provider's change feed, so
  a re-run over an unchanged drive costs a single request, and both detect renames and moves rather
  than reporting them as a deletion plus a new file. Credentials are worker-level
  (`CORTEX_GDRIVE_*`, `CORTEX_ONEDRIVE_*`); a kind is advertised only when that provider is
  configured. No shared media mount is required — files are fetched lazily by whichever worker runs
  a node task against them.
* Initial public release