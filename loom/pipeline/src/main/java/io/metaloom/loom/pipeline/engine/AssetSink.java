package io.metaloom.loom.pipeline.engine;

import java.util.Map;

import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.PortPayload;

/**
 * Where node output goes when a node is marked {@code syncToLoom}.
 *
 * <p>Separate from {@link RunStateStore}, which records how a <em>run</em> is
 * progressing. This is about the media itself: the run is the occasion on which a
 * hash was computed, but the hash belongs to the asset and outlives the run.</p>
 *
 * <p>The engine holds this as an interface for the same reason it holds
 * {@link NodeDispatcher} as one - {@code loom/pipeline} is barred from depending on
 * Loom internals by the build, and the whole evaluation model stays testable
 * without a database.</p>
 *
 * <p>⚠️ Called on the engine thread with its monitor held, so an implementation must
 * not block for long and must not throw - a persistence problem should not derail
 * the run that produced the data.</p>
 */
@FunctionalInterface
public interface AssetSink {

	/**
	 * Persist what a node produced against the media it was computed from.
	 *
	 * @param media   the item, carrying at least its path and size
	 * @param nodeId  the node that produced the outputs, for diagnostics
	 * @param outputs the node's outputs, keyed by output port id
	 */
	void persist(MediaRef media, String nodeId, Map<String, PortPayload> outputs);

	/**
	 * Discards everything.
	 *
	 * <p>The default, so a run behaves exactly as before unless a sink is installed.</p>
	 */
	AssetSink NOOP = (media, nodeId, outputs) -> {
	};

}
