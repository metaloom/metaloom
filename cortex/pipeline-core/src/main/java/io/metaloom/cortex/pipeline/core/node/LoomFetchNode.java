package io.metaloom.cortex.pipeline.core.node;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.api.node.NodeResult;

/**
 * Pipeline node that fetches user metadata (tags, annotations, descriptions) from the Loom backend server.
 * This allows downstream nodes (e.g. LLM) to incorporate user-provided context into their processing.
 *
 * <p>The actual data is stored on the {@link LoomMedia} xattr/heap store so downstream nodes
 * can read it via typed keys.</p>
 */
public class LoomFetchNode extends AbstractPipelineNode {

	private static final Logger log = LoggerFactory.getLogger(LoomFetchNode.class);

	public static final String NODE_ID = "loom-fetch";

	private final LoomMetadataFetcher fetcher;

	public LoomFetchNode(int concurrency, LoomMetadataFetcher fetcher) {
		super(NODE_ID, "Loom Metadata Fetch", NodeMode.PARALLEL, false, concurrency);
		this.fetcher = fetcher;
	}

	public LoomFetchNode(LoomMetadataFetcher fetcher) {
		this(2, fetcher);
	}

	@Override
	public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		long start = System.currentTimeMillis();
		try {
			if (fetcher == null) {
				return NodeResult.skipped(id(), "No Loom client configured (offline mode)");
			}
			fetcher.fetch(media);
			long elapsed = System.currentTimeMillis() - start;
			return NodeResult.success(id(), elapsed);
		} catch (Exception e) {
			long elapsed = System.currentTimeMillis() - start;
			log.error("Error fetching Loom metadata for {}: {}", media.absolutePath(), e.getMessage(), e);
			return NodeResult.failed(id(), elapsed, e.getMessage());
		}
	}

	/**
	 * Strategy interface for fetching metadata from Loom. This allows the node to be decoupled
	 * from a specific LoomClient implementation.
	 */
	@FunctionalInterface
	public interface LoomMetadataFetcher {

		/**
		 * Fetch user metadata (tags, annotations, descriptions) from Loom and store on the media.
		 *
		 * @param media the media to enrich
		 * @throws Exception if the fetch fails
		 */
		void fetch(LoomMedia media) throws Exception;
	}
}
