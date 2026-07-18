package io.metaloom.loom.rest.model.processor.message;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.rest.model.RestModel;

/**
 * A batch of media items discovered by a source node.
 *
 * <p>Body of {@link ProcessorMessageType#SOURCE_ITEMS}. Batching is not an
 * optimisation here but a requirement: a 100 000 file scan must not become
 * 100 000 WebSocket frames.</p>
 *
 * <p>The processor waits for a {@link SourceItemsAckMessage} carrying the same
 * {@link #seq} before sending the next batch. That is the backpressure mechanism -
 * without it a fast scanner buries a slower engine.</p>
 */
public class SourceItemsMessage implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The pipeline run these items belong to")
	private UUID runUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Monotonic batch sequence number, starting at 0")
	private long seq;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The discovered media items - references, never content")
	private List<MediaRef> items;

	public UUID getRunUuid() {
		return runUuid;
	}

	public SourceItemsMessage setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public long getSeq() {
		return seq;
	}

	public SourceItemsMessage setSeq(long seq) {
		this.seq = seq;
		return this;
	}

	public List<MediaRef> getItems() {
		return items;
	}

	public SourceItemsMessage setItems(List<MediaRef> items) {
		this.items = items;
		return this;
	}
}
