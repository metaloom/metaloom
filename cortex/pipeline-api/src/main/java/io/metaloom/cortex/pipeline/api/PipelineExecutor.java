package io.metaloom.cortex.pipeline.api;

import java.util.stream.Stream;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Executor that processes a stream of media items through a pipeline.
 * The executor handles dependency resolution, parallel scheduling, per-node concurrency,
 * caching, and event bus communication.
 */
public interface PipelineExecutor {

	/**
	 * Execute the given pipeline on a single media item.
	 *
	 * @param pipeline the pipeline definition
	 * @param media    the media item to process
	 * @return the result of the pipeline execution
	 */
	PipelineResult execute(Pipeline pipeline, LoomMedia media);

	/**
	 * Execute the given pipeline on a stream of media items.
	 *
	 * @param pipeline the pipeline definition
	 * @param media    stream of media items
	 * @return stream of pipeline results (order may differ from input due to parallelism)
	 */
	Stream<PipelineResult> execute(Pipeline pipeline, Stream<LoomMedia> media);

	/**
	 * Shut down executor thread pools and release resources.
	 */
	void shutdown();
}
