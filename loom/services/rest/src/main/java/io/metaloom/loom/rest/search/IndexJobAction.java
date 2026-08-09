package io.metaloom.loom.rest.search;

/**
 * What an index maintenance job does.
 *
 * <p>
 * Not every index supports every action, which is why {@code SearchIndexDescriptor} carries the supported set rather than the UI deciding: a lexical
 * index maintained by database triggers has nothing to delta-sync and nothing meaningful to drop, and hiding those buttons in the client would leave
 * the routes reachable by anyone with a terminal.
 * </p>
 */
public enum IndexJobAction {

	/**
	 * Discard the index content and rebuild it from the system of record.
	 *
	 * <p>
	 * Always safe in the sense that nothing is lost - every index here is a declared, rebuildable cache - but it walks the whole corpus, and the index
	 * answers with less than the truth while it runs.
	 * </p>
	 */
	REINDEX,

	/**
	 * Bring the index into step without rebuilding: write what is missing, refresh what changed, and remove entries whose source rows are gone.
	 *
	 * <p>
	 * The cheap counterpart to {@link #REINDEX}, and the only one of the two that removes orphans, which a rebuild gets for free by starting empty.
	 * </p>
	 */
	DELTA_SYNC,

	/**
	 * Empty the index without refilling it.
	 *
	 * <p>
	 * The operation behind retiring a superseded embedding model. Queries against the dropped index return nothing until a reindex, which is a visible
	 * outage of a feature rather than data loss - the vectors remain in Postgres.
	 * </p>
	 */
	DROP
}
