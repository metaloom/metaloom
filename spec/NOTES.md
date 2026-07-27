 /model claude-opus-4-8

* implement TTS / ASR solution - test it first
* FingerprintDedupNode ? We need to implement this workflow
* Review and rework the face detection / face cluster / cluster confirmation workflow

* Nodes report COMPLETED but there is also SUCESS and FAILED: Analyze the response flow for nodes. This seem to be a mess right now. We need to find a clear structure and document it

* Explore the idea to have the agentic loop also be able to create a loom pipeline. It can design, verify a pipeline via MCP.
* Chat needs a way to visualize the pipeline (TODO: How to run the pipline in an isolate environment for the agent?)

* Add sidecar examples for llamacpp, vlm via vllm + Document sidecars in website. Add sidecar support to helm chart?

* Add stop support for agentic loop

* binary handling  - frontend delivery / CDN like encoding?
* binary result handling - How to store thumbnails? Where how? Via network? Explore this


# Pending
* ideogram sidecart

* Implement spec/plans/imagegen-node.md.
 - start with ideogram-sidecar and test it out. I want to see a generated image

 # Finished
* Move sidecar containers to sidecards folder
* Update the loom helm chart
* Fix broken images + localhost check
* helm chart
* dagger node collection handler
* caption vs. video caption
