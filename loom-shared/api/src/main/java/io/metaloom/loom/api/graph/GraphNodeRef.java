package io.metaloom.loom.api.graph;

import java.util.UUID;

/**
 * One vertex of the asset relationship graph: what kind of thing it is, and which row it is.
 *
 * <p>
 * The kind is a string rather than an enum on purpose. The graph's shape follows the schema, and the schema grows - collections, remixes and person
 * clusters all arrived after assets and tags did. An enum would make every new relation a change to this module and a coordinated release; a string
 * makes it a change to whichever service projects the edge. {@link #KIND_ASSET} and friends are the ones that exist today, not the ones that are
 * allowed.
 * </p>
 */
public record GraphNodeRef(String kind, UUID uuid) {

	public static final String KIND_ASSET = "asset";

	public static final String KIND_TAG = "tag";

	public static final String KIND_COLLECTION = "collection";

	public static final String KIND_REMIX = "remix";

	public static final String KIND_PERSON = "person";

	public GraphNodeRef {
		if (kind == null || kind.isBlank()) {
			throw new IllegalArgumentException("A graph node needs a kind");
		}
		if (uuid == null) {
			throw new IllegalArgumentException("A graph node needs a uuid");
		}
	}

	public static GraphNodeRef asset(UUID uuid) {
		return new GraphNodeRef(KIND_ASSET, uuid);
	}

	public static GraphNodeRef tag(UUID uuid) {
		return new GraphNodeRef(KIND_TAG, uuid);
	}

	public static GraphNodeRef collection(UUID uuid) {
		return new GraphNodeRef(KIND_COLLECTION, uuid);
	}

	public static GraphNodeRef remix(UUID uuid) {
		return new GraphNodeRef(KIND_REMIX, uuid);
	}

	public static GraphNodeRef person(UUID uuid) {
		return new GraphNodeRef(KIND_PERSON, uuid);
	}

	public boolean isAsset() {
		return KIND_ASSET.equals(kind);
	}

	@Override
	public String toString() {
		return kind + ":" + uuid;
	}
}
