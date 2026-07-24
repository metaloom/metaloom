package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;

/**
 * Filters out duplicate media that has been seen before, based on an upstream hash
 * or fingerprint value. The first occurrence passes; subsequent duplicates are rejected.
 *
 * <p>The seen-set is held in memory for the lifetime of the pipeline execution. For
 * persistent deduplication, use the {@code dedup} nodes and a database-backed store.</p>
 */
public class DuplicateFilterNode extends AbstractFilterNode {

	private static final Logger log = LoggerFactory.getLogger(DuplicateFilterNode.class);

	private final String upstreamNodeId;
	private final String outputKey;
	private final Set<String> seen = ConcurrentHashMap.newKeySet();

	private DuplicateFilterNode(Builder builder) {
		super(builder.id, builder.name);
		this.upstreamNodeId = builder.upstreamNodeId;
		this.outputKey = builder.outputKey;
	}

	@Override
	protected boolean evaluate(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		String identity = resolveIdentity(upstreamResults);
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

	private String resolveIdentity(Map<String, NodeResult> upstreamResults) {
		if (upstreamResults == null) {
			return null;
		}
		NodeResult nr = upstreamResults.get(upstreamNodeId);
		if (nr == null || nr.getOutput() == null) {
			return null;
		}
		Object val = nr.getOutput().get(outputKey);
		return val != null ? val.toString() : null;
	}

	@Override
	protected String rejectReason(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return "duplicate detected";
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
		private Set<String> dependencies = Set.of();
		private String upstreamNodeId;
		private String outputKey;

		private Builder(String id) {
			this.id = id;
			this.name = id;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder dependencies(Set<String> dependencies) {
			this.dependencies = dependencies;
			return this;
		}

		/** The upstream node that produces the identity value (e.g. "sha512", "fingerprint"). */
		public Builder upstreamNodeId(String upstreamNodeId) {
			this.upstreamNodeId = upstreamNodeId;
			return this;
		}

		/** The output key containing the identity value (e.g. "sha512", "fingerprint"). */
		public Builder outputKey(String outputKey) {
			this.outputKey = outputKey;
			return this;
		}

		public DuplicateFilterNode build() {
			if (upstreamNodeId == null || outputKey == null) {
				throw new IllegalStateException("upstreamNodeId and outputKey are required");
			}
			return new DuplicateFilterNode(this);
		}
	}
}
