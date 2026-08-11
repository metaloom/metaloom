package io.metaloom.loom.db.model.share;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.Element;

/**
 * A mark a share visitor drew on the media - a timecode, a region of the frame, or both.
 *
 * <p>
 * Deliberately not {@code annotation}. That table requires a {@code creator_uuid} referencing a real user, which a share visitor does not have, and
 * it stores regions in pixels and times in whole seconds. This viewer is full-bleed and responsive, so a pixel box means nothing across viewport
 * sizes, and a whole second is 25 frames of ambiguity at the exact moment a reviewer is trying to be precise about a cut.
 * </p>
 *
 * <p>
 * Coordinates here are <b>normalised 0..1</b> against the media's own dimensions, and times are <b>seconds as a float</b>.
 * </p>
 */
public interface ShareAnnotation extends Element<ShareAnnotation> {

	UUID getShareUuid();

	ShareAnnotation setShareUuid(UUID shareUuid);

	UUID getAssetUuid();

	ShareAnnotation setAssetUuid(UUID assetUuid);

	String getKind();

	ShareAnnotation setKind(String kind);

	default ShareAnnotationKind kind() {
		return ShareAnnotationKind.parse(getKind());
	}

	default ShareAnnotation setKind(ShareAnnotationKind kind) {
		return setKind(kind == null ? null : kind.name());
	}

	/** Seconds from the start of the media. */
	Double getTimeFrom();

	ShareAnnotation setTimeFrom(Double timeFrom);

	/** Seconds from the start of the media; null for a point in time rather than a range. */
	Double getTimeTo();

	ShareAnnotation setTimeTo(Double timeTo);

	/** Normalised 0..1 from the left edge. */
	Double getAreaX();

	ShareAnnotation setAreaX(Double areaX);

	/** Normalised 0..1 from the top edge. */
	Double getAreaY();

	ShareAnnotation setAreaY(Double areaY);

	/** Normalised fraction of the media width. */
	Double getAreaWidth();

	ShareAnnotation setAreaWidth(Double areaWidth);

	/** Normalised fraction of the media height. */
	Double getAreaHeight();

	ShareAnnotation setAreaHeight(Double areaHeight);

	String getText();

	ShareAnnotation setText(String text);

	/**
	 * The visitor name as it stood when this was written.
	 *
	 * <p>
	 * Denormalised from {@link Share#getVisitorName()} on purpose: what somebody was called when they said something is a historical fact, and reading
	 * it back through a join would let a later edit of the share rewrite the past.
	 * </p>
	 */
	String getAuthorName();

	ShareAnnotation setAuthorName(String authorName);

	Instant getCreated();

	ShareAnnotation setCreated(Instant created);

	Instant getEdited();

	ShareAnnotation setEdited(Instant edited);
}
