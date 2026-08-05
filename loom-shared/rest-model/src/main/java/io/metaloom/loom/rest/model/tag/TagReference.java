package io.metaloom.loom.rest.model.tag;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.common.AbstractNamedReference;

public class TagReference extends AbstractNamedReference<TagReference> {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Spatial or temporal region of the asset that the tag references. Only set for region tags.")
	private AreaInfo area;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Identity of this placement of the tag on the asset. A tag may sit on one asset several times - once per face, once per timecode - and this is what identifies the one you mean.")
	private UUID placementUuid;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Which node kind attached the tag, or 'manual' when a person did. This is how machine tags are told apart from curated ones.")
	private String nodeKind;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Pipeline node id of the writer, so two instances of one node kind stay distinguishable. Absent for a person.")
	private String nodeId;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("How sure the writer was, 0.0 - 1.0. Absent when the question does not apply, which is the normal case for a person.")
	private Float confidence;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("When the tag was attached to this asset. Not when the tag itself was created.")
	private Instant attached;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("The principal that made the call, person or worker token.")
	private UUID attachedBy;

	public AreaInfo getArea() {
		return area;
	}

	public TagReference setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	public UUID getPlacementUuid() {
		return placementUuid;
	}

	public TagReference setPlacementUuid(UUID placementUuid) {
		this.placementUuid = placementUuid;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public TagReference setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public TagReference setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public Float getConfidence() {
		return confidence;
	}

	public TagReference setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	public Instant getAttached() {
		return attached;
	}

	public TagReference setAttached(Instant attached) {
		this.attached = attached;
		return this;
	}

	public UUID getAttachedBy() {
		return attachedBy;
	}

	public TagReference setAttachedBy(UUID attachedBy) {
		this.attachedBy = attachedBy;
		return this;
	}

	@Override
	public TagReference self() {
		return this;
	}

}
