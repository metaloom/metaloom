package io.metaloom.loom.rest.model.processor.message;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Acknowledges a {@link SourceItemsMessage}, releasing the processor to send the
 * next batch.
 *
 * <p>Body of {@link ProcessorMessageType#SOURCE_ITEMS_ACK}.</p>
 */
public class SourceItemsAckMessage implements RestModel {

	@JsonProperty(required = true)
	private UUID runUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Sequence number of the batch being acknowledged")
	private long seq;

	public UUID getRunUuid() {
		return runUuid;
	}

	public SourceItemsAckMessage setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public long getSeq() {
		return seq;
	}

	public SourceItemsAckMessage setSeq(long seq) {
		this.seq = seq;
		return this;
	}
}
