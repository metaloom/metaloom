package io.metaloom.loom.db.jooq.dao.tag;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.Tag;

/**
 * ⚠️ The placement fields are read through aliases, not through their column names: {@code tag_asset} and {@code tag} both have {@code uuid},
 * {@code created} and {@code creator_uuid}, and jOOQ's {@code fetchInto} maps by name. {@code TagDaoImpl.assetTags} selects them as
 * {@code placement_uuid}, {@code attached} and {@code attached_by} for that reason - renaming a field here without renaming the alias there silently
 * yields nulls.
 */
public class AssetTagImpl extends TagImpl implements AssetTag {

	private Long timeFrom;
	private Long timeTo;
	private Integer areaStartX;
	private Integer areaStartY;
	private Integer areaWidth;
	private Integer areaHeight;

	private UUID placementUuid;
	private String nodeKind;
	private String nodeId;
	private String producerVersion;
	private Float confidence;
	private Instant attached;
	private UUID attachedBy;

	@Override
	public UUID getPlacementUuid() {
		return placementUuid;
	}

	@Override
	public AssetTag setPlacementUuid(UUID placementUuid) {
		this.placementUuid = placementUuid;
		return this;
	}

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public AssetTag setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getNodeId() {
		return nodeId;
	}

	@Override
	public AssetTag setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public AssetTag setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	@Override
	public Float getConfidence() {
		return confidence;
	}

	@Override
	public AssetTag setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	@Override
	public Instant getAttached() {
		return attached;
	}

	@Override
	public AssetTag setAttached(Instant attached) {
		this.attached = attached;
		return this;
	}

	@Override
	public UUID getAttachedBy() {
		return attachedBy;
	}

	@Override
	public AssetTag setAttachedBy(UUID attachedBy) {
		this.attachedBy = attachedBy;
		return this;
	}

	@Override
	public Long getTimeFrom() {
		return timeFrom;
	}

	@Override
	public Tag setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public Long getTimeTo() {
		return timeTo;
	}

	@Override
	public Tag setTimeTo(Long timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	@Override
	public Integer getAreaHeight() {
		return areaHeight;
	}

	@Override
	public Tag setAreaHeight(Integer areaHeight) {
		this.areaHeight = areaHeight;
		return this;
	}

	@Override
	public Integer getAreaWidth() {
		return areaWidth;
	}

	@Override
	public Tag setAreaWidth(Integer areaWidth) {
		this.areaWidth = areaWidth;
		return this;
	}

	@Override
	public Integer getAreaStartX() {
		return areaStartX;
	}

	@Override
	public Tag setAreaStartX(Integer areaStartX) {
		this.areaStartX = areaStartX;
		return this;
	}

	@Override
	public Integer getAreaStartY() {
		return areaStartY;
	}

	@Override
	public Tag setAreaStartY(Integer areaStartY) {
		this.areaStartY = areaStartY;
		return this;
	}
}
