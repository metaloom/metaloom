package io.metaloom.cortex.api.node.context;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.impl.NodeContextImpl;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.MediaType;

/**
 * Context for a single node invocation. Wraps the typed input {@code I} and accumulates
 * metadata about the processing (origin, skip reason, timing).
 *
 * @param <I> the input type
 */
public interface NodeContext<I> {

	static NodeContext<LoomMedia> create(LoomMedia media) {
		return new NodeContextImpl<>(media);
	}

	/**
	 * Returns the typed input for this node invocation.
	 */
	I input();

	/**
	 * Returns the underlying {@link LoomMedia} (convenience accessor).
	 */
	LoomMedia media();

	default <T extends LoomMedia> T media(MediaType<T> type) {
		return media().of(type);
	}

	/**
	 * Returns the time in milliseconds since the creation of this context.
	 */
	long duration();

	NodeContext<I> skipped(String reason);

	NodeContext<I> origin(ResultOrigin origin);

	ResultOrigin origin();

	NodeContext<I> failure(String cause);

	<O> NodeResult<O> next();

	<O> NodeResult<O> abort();

	NodeContext<I> print(String string, String string2);

	NodeContext<I> info(String msg);
}
