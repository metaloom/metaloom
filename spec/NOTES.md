 /model claude-opus-4-8

* implement TTS / ASR solution - test it first
* FingerprintDedupNode ? We need to implement this workflow
* Review and rework the face detection / face cluster / cluster confirmation workflow

* Nodes report COMPLETED but there is also SUCESS and FAILED: Analyze the response flow for nodes. This seem to be a mess right now. We need to find a clear structure and document it

* Explore the idea to have the agentic loop also be able to create a loom pipeline. It can design, verify a pipeline via MCP.
* Chat needs a way to visualize the pipeline (TODO: How to run the pipline in an isolate environment for the agent?)

* Add sidecar examples for llamacpp, vlm via vllm + Document sidecars in website. Add sidecar support to helm chart?

* Add stop/start support for agentic loop

* binary handling  - frontend delivery / CDN like encoding?
* binary result handling - How to store thumbnails? Where how? Via network? Explore this






# Pending


 # Finished
* Implement spec/plans/imagegen-node.md.
 - start with ideogram-sidecar and test it out. I want to see a generated image
* ideogram sidecart
* Move sidecar containers to sidecards folder
* Update the loom helm chart
* Fix broken images + localhost check
* helm chart
* dagger node collection handler
* caption vs. video caption


# Feedback

AbstractBasicNodeTest.assertProcessed expects SKIPPED on a re-run, but every node with a LocalResultCache re-emits its cached value and returns SUCCESS. That's 33 of the 41 failures (hash ×4, fingerprint, scene-detection, whisper). I verified against git show HEAD that this predates the refactor — the cache-hit path already returned next(). Either the base class or the cache-hit state is wrong.
examples/cortex-custom does not compile, unrelated to ports: its Dagger component omits S3Module, so SqsS3EventSource/WebhookS3EventSource can't be provided for MonitoringService. Introduced with the S3 source/sink commit. I excluded it from the build commands rather than fix it blind. — FIXED 2026-07-30: S3Module moved from cortex/cli to cortex/core (which already depends on cortex-s3-common) and added to the example's component; the module is no longer excluded from the build.
Remaining 8 failures are environmental: hard-coded /extra/vid/* paths, OpenCV natives unloaded, no local Ollama/SmolVLM, xattr unsupported on the test filesystem.

Per CLAUDE.md I updated spec/features/pipeline/NODE_DATA_TYPES.md §11 (the "still outstanding" table was stale — pipeline-common was listed as the compile blocker) and closed §9.2 item 9 (cross-tree conformance test now exists and is green) and item 8 (cache line format).