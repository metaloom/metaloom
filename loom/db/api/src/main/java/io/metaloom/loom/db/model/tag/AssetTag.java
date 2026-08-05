package io.metaloom.loom.db.model.tag;

import java.time.Instant;
import java.util.UUID;

/**
 * A tag together with <em>one placement of it on one asset</em>.
 *
 * <p>
 * The distinction matters since V2.71. A {@link Tag} is a global object - <code>UNIQUE (name, collection)</code> - and the same tag may now sit on one
 * asset several times: once per face in a photo, once per timecode in a video. Everything below the tag's own fields therefore belongs to the
 * <code>tag_asset</code> row rather than to the tag: the region, who attached it, and the placement's own {@link #getPlacementUuid() uuid}, which is
 * what a caller removes when it wants one region rather than every occurrence of the tag.
 * </p>
 *
 * <p>
 * ⚠️ {@link #getUuid()} is the <strong>tag's</strong> uuid, inherited from {@link Tag}. The placement has its own.
 * </p>
 */
public interface AssetTag extends Tag {

	/** The {@code node_kind} value that means "a person did this". Mirrors the same convention on {@code detection}. */
	String MANUAL_NODE_KIND = "manual";

	/**
	 * Identity of this placement (<code>tag_asset.uuid</code>), or <code>null</code> for a transient one that has not been written yet.
	 */
	UUID getPlacementUuid();

	AssetTag setPlacementUuid(UUID placementUuid);

	/**
	 * Which node kind attached the tag, or the literal <code>manual</code> when a person did.
	 *
	 * <p>
	 * This is the field that separates machine tags from curated ones, and the reason a node may withdraw its own work without endangering anybody
	 * else's. It defaults to <code>manual</code> in the schema: an attachment that does not say who wrote it is treated as human, because a machine row
	 * mislabelled as human is merely not filtered out, while the reverse could be deleted by a node reconciling.
	 * </p>
	 */
	String getNodeKind();

	AssetTag setNodeKind(String nodeKind);

	/** Pipeline node id of the writer, so two instances of one node kind stay distinguishable. <code>null</code> for a person. */
	String getNodeId();

	AssetTag setNodeId(String nodeId);

	/** Version of the answer the writer stands behind; it changes when the meaning of the tag changes. */
	String getProducerVersion();

	AssetTag setProducerVersion(String producerVersion);

	/** How sure the writer was, 0.0 - 1.0, or <code>null</code> when the question does not apply - the normal case for a person. */
	Float getConfidence();

	AssetTag setConfidence(Float confidence);

	/** When the tag was attached to the asset. Distinct from {@link #getCreated()}, which is when the tag itself was first created. */
	Instant getAttached();

	AssetTag setAttached(Instant attached);

	/** The principal that made the call, person or worker token. Authorship is {@link #getNodeKind()}; this is accountability. */
	UUID getAttachedBy();

	AssetTag setAttachedBy(UUID attachedBy);

	Integer getAreaStartY();

	Tag setAreaStartY(Integer areaStartY);

	Integer getAreaStartX();

	Tag setAreaStartX(Integer areaStartX);

	Integer getAreaWidth();

	Tag setAreaWidth(Integer areaWidth);

	Integer getAreaHeight();

	Tag setAreaHeight(Integer areaHeight);

	Long getTimeTo();

	Tag setTimeTo(Long timeTo);

	Long getTimeFrom();

	Tag setTimeFrom(Long timeFrom);

	/** Whether this placement carries a region at all; an asset-level tag has none. */
	default boolean hasRegion() {
		return getTimeFrom() != null || getTimeTo() != null
			|| getAreaStartX() != null || getAreaStartY() != null
			|| getAreaWidth() != null || getAreaHeight() != null;
	}

	/** Whether a pipeline attached this tag rather than a person. */
	default boolean isMachineWritten() {
		return getNodeKind() != null && !MANUAL_NODE_KIND.equals(getNodeKind());
	}
}
