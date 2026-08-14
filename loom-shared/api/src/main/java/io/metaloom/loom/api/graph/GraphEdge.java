package io.metaloom.loom.api.graph;

import java.util.UUID;

/**
 * One relationship, as the index stores it.
 *
 * <p>
 * Edges are <b>undirected in meaning and directed in storage</b>. "This asset carries this tag" and "this tag is on this asset" are the same fact, and
 * every query in {@link AssetGraphIndex} traverses both ways, but the pair is stored with a stable orientation so that re-projecting the same row
 * twice produces the same edge rather than two.
 * </p>
 *
 * <p>
 * The type names a relation in the schema, not a direction. {@link #TYPE_TAGGED} is {@code tag_asset}, {@link #TYPE_IN_COLLECTION} is
 * {@code collection_asset}, {@link #TYPE_IN_REMIX} is {@code remix_member}, {@link #TYPE_DEPICTS} is the asset-to-person path through
 * {@code detection}. A relation that gains a column keeps its type; a new table gets a new one.
 * </p>
 */
public record GraphEdge(GraphNodeRef from, String type, GraphNodeRef to) {

	/** {@code tag_asset}: an asset carries a tag. */
	public static final String TYPE_TAGGED = "TAGGED";

	/** {@code collection_asset}: an asset belongs to a collection. */
	public static final String TYPE_IN_COLLECTION = "IN_COLLECTION";

	/** {@code remix_member}: an asset is a member of a remix - a named group of versions of one another. */
	public static final String TYPE_IN_REMIX = "IN_REMIX";

	/** {@code detection} plus its person link: a person appears in an asset. */
	public static final String TYPE_DEPICTS = "DEPICTS";

	public GraphEdge {
		if (from == null || to == null) {
			throw new IllegalArgumentException("An edge needs both endpoints");
		}
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("An edge needs a type");
		}
	}

	public static GraphEdge tagged(UUID assetUuid, UUID tagUuid) {
		return new GraphEdge(GraphNodeRef.tag(tagUuid), TYPE_TAGGED, GraphNodeRef.asset(assetUuid));
	}

	public static GraphEdge inCollection(UUID assetUuid, UUID collectionUuid) {
		return new GraphEdge(GraphNodeRef.collection(collectionUuid), TYPE_IN_COLLECTION, GraphNodeRef.asset(assetUuid));
	}

	public static GraphEdge inRemix(UUID assetUuid, UUID remixUuid) {
		return new GraphEdge(GraphNodeRef.remix(remixUuid), TYPE_IN_REMIX, GraphNodeRef.asset(assetUuid));
	}

	public static GraphEdge depicts(UUID assetUuid, UUID personUuid) {
		return new GraphEdge(GraphNodeRef.person(personUuid), TYPE_DEPICTS, GraphNodeRef.asset(assetUuid));
	}

	@Override
	public String toString() {
		return from + " -[" + type + "]-> " + to;
	}
}
