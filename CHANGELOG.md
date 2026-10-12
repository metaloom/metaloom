# 1.0.0-SNAPSHOT

* **Security:** a person holding a share link can no longer edit or delete feedback somebody else
  left through the same link. Each visitor's session carries a random visitor id inside the signed
  session token; comments, marks and reactions are stamped with it (`visitor_id`, `V2.120`), and
  `PUT`/`DELETE` on another visitor's row answers **403**. Before, any visitor could delete any other
  visitor's feedback by uuid.
  * The public listings and create responses carry `own: true|false` per row; the share viewer shows
    delete only on the visitor's own feedback and toggles only their own reaction.
  * Two people reacting the same way through one link are now two reactions, not one.
  * A lapsed session reopened in the same tab keeps its visitor: `POST /shares/:slug/sessions` takes
    an optional `previousSessionToken`.
  * Feedback written before the upgrade belongs to no visitor and is read-only through the link. A
    session token issued before the upgrade can still read, and is sent back to the gate (401) on
    its first write.
* **Fix:** switching to another asset no longer requests its first preview or stream with the
  previous asset's media token.
* **Fix:** the face panel on an asset groups faces by their real cluster membership; it used to
  report "0 clusters" whatever the clusters held.
* **Change:** the LLM detection tab is marked as a preview with sample data; its actions are disabled
  until there is a backend behind them.
* **Fix:** about 45 API calls in the web UI now report failures with status and trace id, and an
  expired session on any of them signs the user out cleanly instead of showing a raw error.
* **Fix:** a user or an API token can hold more than one direct permission. `user_permission` and
  `token_permission` were keyed on the subject alone, so a second direct grant was a primary-key
  violation; both are keyed on (subject, permission) now and lose their unused `resource` column
  (`V2.118`). Deleting a token removes its grants instead of failing.
* **Fix:** `vector_config` has a primary key and real foreign keys for its creator and editor, and
  `token.editor_uuid` references a user (`V2.119`). The integrity report keeps its checks for older
  data, but the schema now refuses those rows. `vector_config` is readable and writable from code
  through a new DAO.
* **New:** the pipeline editor shows where a pipeline's time goes. A *Performance* tab beside the
  system log ranks the steps by execution time over the last 1, 10, 20 or 50 runs, adds up the total,
  the reused executions and the estimated time saved, and drills into one step: mean, median, 95th
  percentile, slowest, the spread of its durations and its cost per run. Each node card shows its
  accumulated time and reused share, in debug mode or not. Backed by
  `GET /api/v1/pipelines/:uuid/node-stats` (`READ_PIPELINE_RUN`).
* **New:** a step that reused a result instead of computing it is marked *Reused* in the run view.
  `pipeline_node_task` records where each result came from (`origin`, `V2.117`), and the task records
  of `GET .../items/:itemUuid/tasks` carry it. The pipeline-level `reuseResults` adoption is recorded
  as reused too.
* **Behaviour change:** nodes no longer redo work they already did. Before a node processes a file
  it asks Loom whether it produced a result for that file with the same settings and the same input
  values, in any earlier run of any pipeline; if so it hands the stored result downstream and skips
  the work. A pipeline that has seen its files before now runs in the time the lookups take.
  * Every node that reuses has a new **Recompute** parameter. On, the node does the work again and
    replaces the stored result. It can also be set as `recompute: true` on a pipeline node
    definition.
  * Nodes whose work is a side effect or depends on reviewer decisions always run: `move`, `assign`,
    `s3-sink`, `filter`, `tag`, `scene-layout` and the dedup nodes.
  * The lookup replaces the asset load a node already made, so a miss costs nothing extra. A reused
    step records `REMOTE` as its origin in the asset's node results.
  * Files processed before the upgrade have no stored result; their first run computes as before.
* **New:** `asset_node_output` (`V2.116`) and `GET|POST|DELETE /api/v1/assets/:uuid/node-outputs`,
  plus the lookup `GET /api/v1/assets/{uuid|sha512/:hash}/node-outputs/:nodeKind/:reuseKey`. Reads
  need `READ_ASSET`, writes and deletes `UPDATE_ASSET`. `DELETE ...?nodeKind=whisper` forgets the
  stored results of one kind of node for an asset without touching its transcript.
* **New:** a storage report at `GET /api/v1/storage`, and the *Admin → Storage* screen over it. It
  answers "what is filling my disk, and how close am I to full": how many elements of each kind exist
  and what they occupy, how much of that is duplicate content the content-addressed store folded into
  one object, how many stored objects nothing references any more, and the free space and watermark
  of every backend. Needs the new `READ_STORAGE` permission, granted to the admin role by `V2.95`.
  * Two byte figures per kind, deliberately. *Claimed* sums every element; *on disk* counts each
    stored object once. On an installation with many derived crops the first can be several times the
    second, and neither alone answers what an operator is asking.
  * An S3 pool reports **not measurable**, never *OK*. A bucket has no capacity, and a green bar for
    a question nobody could ask is worse than no bar.
* **Behaviour change:** uploads of **attachments, person images and account pictures** are now
  refused with **507** once the target volume would drop below `LOOM_STORAGE_MIN_FREE_SPACE`, and
  with **413** above `LOOM_STORAGE_MAX_UPLOAD_SIZE`. Both limits already applied to asset uploads;
  the other three routes bypassed the check entirely and would instead fill the volume and then fail
  with a 500. Installations running close to the 1 GiB default may see uploads start failing that
  previously succeeded — which is the point, but it is a change.
* **New:** `LOOM_STORAGE_WARN_FREE_SPACE` (default 5 GiB) reports a backend as degraded before
  anything is refused, and `LOOM_STORAGE_SPACE_CHECK_INTERVAL_MS` (default 5 min) drives a background
  check that logs a warning or an error when a backend crosses either mark. Startup now fails if the
  warning mark is below the refusal mark, which would make the degraded state unreachable.
* **New metrics:** `loom_storage_free_bytes`, `loom_storage_total_bytes`, `loom_storage_watermark`
  (labelled by `pool`), `loom_storage_attachment_bytes`, `loom_storage_attachment_objects` (labelled
  by `category`) and `loom_storage_upload_rejections_total` (labelled by `reason`).
* **New:** account pictures. Users can upload a profile picture on *Profile*, and it is shown wherever
  the UI renders their name. The picker on that screen previously only ever produced a local preview
  that vanished on the next reload — nothing was uploaded and there was nowhere to put it.
  `POST /api/v1/me/avatar` needs no permission beyond being signed in; `POST /api/v1/users/:uuid/avatar`
  needs `UPDATE_USER`. An account has at most one picture, so an upload replaces rather than appending.
  Deleting a user removes their picture along with the rest of their personal data.

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