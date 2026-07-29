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

# Pipeline Demo UI
Create a new page on the website which features a pipeline editor. This editor should be a lightweight variant of the full-fleged editor that is integrated in the loom-ui.
The editor should allow creation and simulation of pipelines.
It would be thus also useful to have a play / pause button that allows users to simulate how emitted nodes from the source nodes travels along the designed pipeline till they reach the loom-sync.
The source node in this pipeline demo ui should feature an option to emit a single asset or multiple assets.

A area below the pipeline area / view should feature a action log (timestamp, node name, description, input and output) that composes a log of the input and outputs that the pipeline nodes produced. 

The core idea is that this UI helps users in the pipeline design process and also aids in understanding pipeline mechanism.

There should be a dropdown with 3 demo pipelines. (basic, complex, usecase xyz)

There should also be an option to save pipelines, download pipelines and open pipeline JSON. Saving pipelines just persists those to the localstorage of the browser.

There should also be a box that logs pipeline design errors. This should aid the user when he creates pipelines. When the user tries to connect nodes in an invalid way this should directly be shown in the log.

# Page Design Sync

Update the style/design of the doc, announcements and blog pages.
Currently this design deviates from the /features page.
I like you to adapt the design. (e.g. same style and darker background) and give it a similar high quality design look and feel as the /features page.
Try to reuse the CSS to keep updates easier.




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
examples/cortex-custom does not compile, unrelated to ports: its Dagger component omits S3Module, so SqsS3EventSource/WebhookS3EventSource can't be provided for MonitoringService. Introduced with the S3 source/sink commit. I excluded it from the build commands rather than fix it blind.
Remaining 8 failures are environmental: hard-coded /extra/vid/* paths, OpenCV natives unloaded, no local Ollama/SmolVLM, xattr unsupported on the test filesystem.

Per CLAUDE.md I updated spec/features/pipeline/NODE_DATA_TYPES.md §11 (the "still outstanding" table was stale — pipeline-common was listed as the compile blocker) and closed §9.2 item 9 (cross-tree conformance test now exists and is green) and item 8 (cache line format).