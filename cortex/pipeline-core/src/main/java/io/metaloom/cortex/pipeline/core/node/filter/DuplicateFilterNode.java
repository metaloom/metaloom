package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

/**
 * Filters out duplicate media that has been seen before, based on an upstream hash
 * or fingerprint value. The first occurrence passes; subsequent duplicates are rejected.
 *
 * <p>The seen-set is held in memory for the lifetime of the pipeline execution. For
 * persistent deduplication, use the {@code dedup} nodes and a database-backed store.</p>
 */
public class DuplicateFilterNode extends AbstractFilterNode {

	private static final Logger log = LoggerFactory.getLogger(DuplicateFilterNode.class);

	/**
	 * The identity to deduplicate on - any hash family member, so the same filter works behind
	 * {@code sha512}, {@code md5} or {@code fingerprint} without being told which.
	 */
	public static final InputPort<String> IN_HASH = InputPort.one("hash", ContentTypeRegistry.HASH_ANY, String.class);

	private final Set<String> seen = ConcurrentHashMap.newKeySet();

	private DuplicateFilterNode(Builder builder) {
		super(builder.id, builder.name);
	}

	@Override
	protected boolean evaluate(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		String identity = ctx.input(IN_HASH);
		if (identity == null) {
			// No identity available — pass (cannot dedup)
			return true;
		}
		boolean isNew = seen.add(identity);
		if (!isNew) {
			log.debug("Duplicate detected for {} with identity {}", media.absolutePath(), identity);
		}
		return isNew;
	}

	@Override
	protected String rejectReason(NodeContext<LoomMedia> ctx) {
		return "duplicate detected";
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;

		private Builder(String id) {
			this.id = id;
			this.name = id;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public DuplicateFilterNode build() {
			return new DuplicateFilterNode(this);
		}
	}
}
