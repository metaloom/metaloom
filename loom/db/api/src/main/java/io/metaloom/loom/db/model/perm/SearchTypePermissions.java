package io.metaloom.loom.db.model.perm;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import io.metaloom.loom.api.search.SearchEntityType;

/**
 * Which read permission each searchable entity type requires.
 *
 * <p>
 * Search is cross-entity by construction, so the wholesale {@link Permission#READ_SEARCH} gate is not enough: without narrowing, a role that may read
 * tags but not assets would either see assets it must not, or see nothing at all. Every caller that can identify its user narrows the requested types
 * against this map.
 * </p>
 *
 * <p>
 * <b>Why it lives here.</b> Two surfaces must agree on it - {@code SearchEndpointService} in {@code loom/services/rest} and {@code SearchWiring} in
 * {@code loom/services/graphql} - and this is the module that owns {@link Permission} and is visible to both. A second copy would silently expose a
 * newly added {@link SearchEntityType} through whichever surface was forgotten.
 * </p>
 *
 * @see io.metaloom.loom.api.search.SearchProvider
 */
public final class SearchTypePermissions {

	/**
	 * Transcripts and segments are covered by {@link Permission#READ_ASSET}: both are components of an asset and have no permission of their own.
	 */
	private static final Map<SearchEntityType, Permission> TYPE_PERMISSIONS;

	static {
		Map<SearchEntityType, Permission> map = new EnumMap<>(SearchEntityType.class);
		map.put(SearchEntityType.ASSET, Permission.READ_ASSET);
		map.put(SearchEntityType.TRANSCRIPT, Permission.READ_ASSET);
		map.put(SearchEntityType.SEGMENT, Permission.READ_ASSET);
		map.put(SearchEntityType.TAG, Permission.READ_TAG);
		map.put(SearchEntityType.ANNOTATION, Permission.READ_ANNOTATION);
		map.put(SearchEntityType.PERSON, Permission.READ_PERSON);
		map.put(SearchEntityType.COLLECTION, Permission.READ_COLLECTION);
		map.put(SearchEntityType.REMIX, Permission.READ_REMIX);
		map.put(SearchEntityType.LIBRARY, Permission.READ_LIBRARY);
		map.put(SearchEntityType.DETECTION, Permission.READ_DETECTION);
		map.put(SearchEntityType.CLUSTER, Permission.READ_CLUSTER);
		TYPE_PERMISSIONS = Collections.unmodifiableMap(map);
	}

	private SearchTypePermissions() {
	}

	/**
	 * The permission needed to see hits of this type, or {@code null} when the type carries no additional requirement beyond
	 * {@link Permission#READ_SEARCH}.
	 */
	public static Permission required(SearchEntityType type) {
		return TYPE_PERMISSIONS.get(type);
	}

	/**
	 * The message handed back when a type is withheld. Dropped types are always reported - silently returning fewer types is indistinguishable from an
	 * empty index.
	 */
	public static String warning(Permission missing) {
		return "Some entity types were excluded from this search: missing permission " + missing.name() + ".";
	}
}
