package io.metaloom.cortex.pipeline.api;

import java.util.List;
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
	 * After processing, any pending sync-eligible results are flushed to Loom.
	 *
	 * @param pipeline the pipeline definition
	 * @param media    stream of media items
	 * @return stream of pipeline results (order may differ from input due to parallelism)
	 */
	Stream<PipelineResult> execute(Pipeline pipeline, Stream<LoomMedia> media);

	/**
	 * Execute the given pipeline on a batch of media items and flush to Loom at the end.
	 *
	 * @param pipeline the pipeline definition
	 * @param batch    list of media items
	 * @return list of pipeline results
	 */
	default List<PipelineResult> executeBatch(Pipeline pipeline, List<LoomMedia> batch) {
		List<PipelineResult> results = execute(pipeline, batch.stream()).toList();
		flushSync();
		return results;
	}

	/**
	 * Flush any pending sync-eligible node results to Loom.
	 *
	 * @return the number of items flushed
	 */
	int flushSync();

	/**
	 * Shut down executor thread pools and release resources.
	 */
	void shutdown();
}
