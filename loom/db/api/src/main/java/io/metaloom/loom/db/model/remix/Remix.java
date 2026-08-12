package io.metaloom.loom.db.model.remix;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;

/**
 * A named group of assets that are versions of one another - an original plus the cuts, re-encodes
 * and edits made from it.
 *
 * <p>
 * Introduced by {@code V2.100}, which dropped the never-written {@code asset_remix} pair table from
 * {@code V2.8}. A remix is a curation artefact: a person decides that these files belong together.
 * It is not deduplication (identical bytes are one asset, by sha512), and it is not similarity
 * search (that is the embedding and cluster path). See
 * {@code spec/features/remix/REMIX.md}.
 * </p>
 *
 * <p>
 * A remix holds assets, never other remixes.
 * </p>
 */
public interface Remix extends CUDElement<Remix> {

	String getName();

	Remix setName(String name);

	String getDescription();

	Remix setDescription(String description);

	/**
	 * The original this remix is built around, or {@code null} when the group has no source in the
	 * catalogue.
	 *
	 * <p>
	 * Denormalised: the authoritative source is the {@code remix_member} row with role
	 * {@link RemixRole#SOURCE}, and {@link RemixDao#setSource(UUID, UUID)} moves both together inside
	 * one transaction. Deleting the source asset nulls this column rather than cascading, so the
	 * derived assets keep their group.
	 * </p>
	 */
	UUID getSourceAssetUuid();

	Remix setSourceAssetUuid(UUID sourceAssetUuid);

}
