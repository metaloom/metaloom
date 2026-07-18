package io.metaloom.cortex.pipeline.api;

import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Executor that processes a stream of media items through a pipeline.
 * The executor handles dependency resolution, parallel scheduling, per-node concurrency,
 * caching, and event bus communication.
 *
 * <p>The reactive API uses RxJava {@link Flowable} for backpressure-aware stream processing.
 * Per-node concurrency is modelled via {@code flatMap(fn, concurrency)} semantics, and
 * multi-parent dependencies are gathered with {@code Single.zip}.</p>
 */
public interface PipelineExecutor {

	/**
	 * Execute the given pipeline on a single media item. This is a blocking convenience
	 * wrapper around the reactive {@link #execute(Pipeline, Flowable)} method.
	 *
	 * @param pipeline the pipeline definition
	 * @param media    the media item to process
	 * @return the result of the pipeline execution
	 */
	PipelineResult execute(Pipeline pipeline, LoomMedia media);

	/**
	 * Execute the given pipeline reactively on a backpressure-aware stream of media items.
	 * The returned {@link Flowable} respects downstream demand: slow consumers cause the
	 * source to be paused rather than buffering unboundedly.
	 *
	 * <p>Equivalent to {@link #execute(Pipeline, Flowable, PipelineRunContext)} with
	 * {@link PipelineRunContext#none()} — the emitted tracking events carry no Loom
	 * run correlation.</p>
	 *
	 * @param pipeline the pipeline definition
	 * @param media    flowable of media items
	 * @return flowable of pipeline results
	 */
	default Flowable<PipelineResult> execute(Pipeline pipeline, Flowable<LoomMedia> media) {
		return execute(pipeline, media, PipelineRunContext.none());
	}

	/**
	 * Execute the given pipeline reactively, correlating the emitted tracking events
	 * with a Loom {@code pipeline_run} record.
	 *
	 * <p>When {@code runContext} is tracked, every tracking event emitted for this
	 * execution carries the run UUID, and the terminal
	 * {@link io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent.Type#PIPELINE_COMPLETED}
	 * event additionally carries the real elapsed duration and the per-media
	 * aggregate counters. Loom uses these to close out the run record.</p>
	 *
	 * @param pipeline   the pipeline definition
	 * @param media      flowable of media items
	 * @param runContext correlation context for this execution, never {@code null}
	 * @return flowable of pipeline results
	 */
	Flowable<PipelineResult> execute(Pipeline pipeline, Flowable<LoomMedia> media, PipelineRunContext runContext);

	/**
	 * Execute the given pipeline on a batch of media items and flush to Loom at the end.
	 *
	 * @param pipeline the pipeline definition
	 * @param batch    list of media items
	 * @return list of pipeline results
	 */
	default List<PipelineResult> executeBatch(Pipeline pipeline, List<LoomMedia> batch) {
		List<PipelineResult> results = execute(pipeline, Flowable.fromIterable(batch))
				.toList().blockingGet();
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
	 * Shut down executor resources.
	 */
	void shutdown();
}
