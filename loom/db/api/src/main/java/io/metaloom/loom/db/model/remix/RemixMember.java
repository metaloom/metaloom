package io.metaloom.loom.db.model.remix;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.Element;

/**
 * The membership of one asset in one {@link Remix}, together with the handful of asset fields a
 * card needs to render.
 *
 * <p>
 * The asset-side fields ({@link #getFilename()}, {@link #getMimeType()}, {@link #getSha512sum()},
 * {@link #getSize()}) are read from the join in {@link RemixDao#loadMembers(UUID, UUID, int)} so the
 * member list of a remix costs one query rather than one plus N. They are a projection of the asset
 * row, not state of the membership: writing them back has no effect.
 * </p>
 *
 * <p>
 * {@link #getUuid()} is the <strong>membership's</strong> uuid, not the asset's.
 * </p>
 */
public interface RemixMember extends Element<RemixMember> {

	UUID getRemixUuid();

	RemixMember setRemixUuid(UUID remixUuid);

	UUID getAssetUuid();

	RemixMember setAssetUuid(UUID assetUuid);

	RemixRole getRole();

	RemixMember setRole(RemixRole role);

	/** User-defined ordering within the remix. {@code null} sorts last. */
	Integer getOrdinal();

	RemixMember setOrdinal(Integer ordinal);

	/** When the asset was added to the remix. */
	Instant getCreated();

	RemixMember setCreated(Instant created);

	/** Who added it. */
	UUID getCreatorUuid();

	RemixMember setCreatorUuid(UUID creatorUuid);

	// ── Projection of the asset row; see the class javadoc ──────────────────

	String getFilename();

	RemixMember setFilename(String filename);

	String getMimeType();

	RemixMember setMimeType(String mimeType);

	String getSha512sum();

	RemixMember setSha512sum(String sha512sum);

	Long getSize();

	RemixMember setSize(Long size);

	/** Whether this member is the original the remix is built around. */
	default boolean isSource() {
		return RemixRole.SOURCE == getRole();
	}
}
