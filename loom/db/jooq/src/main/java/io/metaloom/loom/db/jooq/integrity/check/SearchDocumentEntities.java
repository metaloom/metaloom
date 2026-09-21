package io.metaloom.loom.db.jooq.integrity.check;

import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.loom.api.search.SearchEntityType;

/**
 * Which table each {@code search_document.entity_type} points into.
 *
 * <p>
 * {@code search_document} is polymorphic - {@code (entity_type, entity_uuid)} is its primary key and
 * {@code entity_uuid} carries no foreign key, because it cannot. That makes it the single most
 * likely source of genuinely dangling rows in the schema, and it is why this map exists.
 * </p>
 *
 * <p>
 * The map is hand-written, but {@code DbIntegrityChecksTest} asserts that it covers every
 * {@link SearchEntityType} constant. A new entity type therefore fails a unit test rather than
 * silently losing coverage - which is the failure mode that matters, since a check that quietly
 * stops checking is worse than no check.
 * </p>
 */
public final class SearchDocumentEntities {

	/** {@code entity_type} wire value to the table whose {@code uuid} it names. */
	static final Map<SearchEntityType, String> TABLES;

	static {
		Map<SearchEntityType, String> tables = new LinkedHashMap<>();
		tables.put(SearchEntityType.ASSET, "asset");
		// A transcript is the one entry here whose uuid is NOT a row's primary key. Since V2.110 a
		// transcript is indexed as one document per timecoded window, keyed by a derived
		// uuid_generate_v5(transcript_uuid, window_index) - see DanglingSearchDocumentCheck, which
		// special-cases this type and re-derives the valid set instead of joining on the table.
		// The mapping stays so the coverage assertion in DbIntegrityChecksTest still holds, and it
		// still names where a window comes from.
		tables.put(SearchEntityType.TRANSCRIPT, "asset_transcript_comp");
		tables.put(SearchEntityType.TAG, "tag");
		tables.put(SearchEntityType.ANNOTATION, "annotation");
		tables.put(SearchEntityType.PERSON, "person");
		tables.put(SearchEntityType.COLLECTION, "collection");
		tables.put(SearchEntityType.REMIX, "remix");
		tables.put(SearchEntityType.LIBRARY, "library");
		// No trigger produces these two yet (V2.58/V2.59 has no refresh function for either), but the
		// type is declared and a document could be written by hand, so they are checked anyway.
		tables.put(SearchEntityType.DETECTION, "detection");
		tables.put(SearchEntityType.SEGMENT, "asset_segment_comp");
		tables.put(SearchEntityType.CLUSTER, "cluster");
		TABLES = Map.copyOf(tables);
	}

	private SearchDocumentEntities() {
	}

	/**
	 * Whether a document of this type would be checked for a dangling subject.
	 *
	 * <p>
	 * Exists for {@code DbIntegrityChecksTest}, which asserts this is true of every
	 * {@link SearchEntityType} - an unmapped type matches no branch of the check and is silently
	 * skipped, which is precisely the kind of quiet gap this subsystem is meant not to have.
	 * </p>
	 */
	public static boolean isMapped(SearchEntityType type) {
		return TABLES.containsKey(type);
	}
}
