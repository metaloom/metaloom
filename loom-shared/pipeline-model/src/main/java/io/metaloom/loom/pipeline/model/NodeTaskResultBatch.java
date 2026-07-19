package io.metaloom.loom.pipeline.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Several node results for one run, sent together.
 *
 * <p>A cheap node over many small files produces one result per item per node. At
 * scale that is an enormous number of very small messages, each with its own frame
 * and its own round trip. Accumulating them trades a little latency for far fewer
 * messages.</p>
 *
 * <p>Batching is a <strong>transport</strong> concern only. Each entry is assimilated
 * exactly as if it had arrived on its own, so retries, dead-lettering and downstream
 * unblocking are unchanged — and one bad result cannot spoil the others, because
 * there is no batch-level verdict.</p>
 */
public class NodeTaskResultBatch {

	/** One result, paired with the item it belongs to. */
	public static class Entry {

		private final String itemId;
		private final NodeTaskResult result;

		@JsonCreator
		public Entry(@JsonProperty("itemId") String itemId, @JsonProperty("result") NodeTaskResult result) {
			this.itemId = Objects.requireNonNull(itemId, "An item id must be set");
			this.result = Objects.requireNonNull(result, "A result must be set");
		}

		public String getItemId() {
			return itemId;
		}

		public NodeTaskResult getResult() {
			return result;
		}
	}

	private final UUID runUuid;
	private final List<Entry> entries;

	@JsonCreator
	public NodeTaskResultBatch(@JsonProperty("runUuid") UUID runUuid,
		@JsonProperty("entries") List<Entry> entries) {
		this.runUuid = runUuid;
		this.entries = entries == null ? List.of() : List.copyOf(entries);
	}

	public UUID getRunUuid() {
		return runUuid;
	}

	/** @return the results, in the order they were produced */
	public List<Entry> getEntries() {
		return entries;
	}

	public int size() {
		return entries.size();
	}

	@Override
	public String toString() {
		return "NodeTaskResultBatch[run=" + runUuid + " results=" + entries.size() + "]";
	}

}
