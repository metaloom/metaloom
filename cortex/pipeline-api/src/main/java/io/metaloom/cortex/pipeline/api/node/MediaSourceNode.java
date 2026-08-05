package io.metaloom.cortex.pipeline.api.node;

import io.metaloom.cortex.api.media.LoomMedia;
import io.reactivex.rxjava3.core.Flowable;

/**
 * A source node that can enumerate the media items it yields.
 *
 * <p>Plain {@link PipelineNode} source implementations only mark themselves via
 * {@link PipelineNode#isSource()} — the media stream itself has to be supplied by
 * the caller of
 * the Cortex source runtime, which forwards the stream to Loom in batches.
 * That forces every caller to reimplement media discovery, which is what this
 * interface removes: a {@code MediaSourceNode} owns its own selection logic and
 * hands the executor a ready {@link Flowable}.</p>
 *
 * <h2>Laziness</h2>
 * <p>{@link #stream()} must return a <em>cold</em> {@link Flowable}: no filesystem
 * or network work may happen until subscription, and every subscription must
 * re-enumerate. The executor subscribes once per run and relies on backpressure,
 * so implementations should stream rather than materialise a full list where the
 * underlying source allows it.</p>
 *
 * <p>The executor that drives this is {@code io.metaloom.cortex.runtime.SourceTaskRunner}. It is
 * named rather than linked on purpose: it lives in {@code cortex/node-runtime}, which depends on
 * this module, so a resolvable {@code @see} here would mean inverting that.</p>
 */
public interface MediaSourceNode extends PipelineNode {

	/**
	 * Enumerate the media items this source yields.
	 *
	 * @return a cold, backpressure-aware flowable of media items; never {@code null}
	 */
	Flowable<LoomMedia> stream();

	/**
	 * A {@code MediaSourceNode} is always a source node.
	 */
	@Override
	default boolean isSource() {
		return true;
	}
}
