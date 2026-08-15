package io.metaloom.loom.api.filter;

import io.metaloom.filter.key.impl.BooleanFilterKey;
import io.metaloom.filter.key.impl.SizeFilterKey;
import io.metaloom.filter.key.impl.StringFilterKey;

/**
 * The keys a list route accepts in {@code ?filter=}, in LHS form — {@code name[eq]=holiday}.
 *
 * <p>
 * A key here is only half the wiring: {@link LoomLHSFilterParser} has to register it before the query string can be parsed at all, and the DAO has to
 * implement it in {@code applyFilter} before it narrows anything. A key registered but not implemented answers 400 naming the type, which is the
 * intended behaviour — filtering assets by a key that only makes sense for tags should say so rather than be ignored.
 * </p>
 */
public final class LoomFilterKey {

	public static final StringFilterKey UUID = new StringFilterKey("uuid");

	public static final StringFilterKey NAME = new StringFilterKey("name");

	public static final StringFilterKey COLLECTION = new StringFilterKey("collection");

	public static final StringFilterKey USERNAME = new StringFilterKey("username");

	/**
	 * The user who created the element, given as a uuid.
	 *
	 * <p>
	 * A uuid rather than a username because the username is mutable: a filter that survives a rename is the one a saved view or a bookmarked URL
	 * needs. Callers holding only a name resolve it through {@code /users} first — the element responses already carry
	 * {@code status.creator.uuid} for exactly this.
	 * </p>
	 */
	public static final StringFilterKey CREATOR = new StringFilterKey("creator");

	/** The user who last edited the element, given as a uuid. Counterpart to {@link #CREATOR}. */
	public static final StringFilterKey EDITOR = new StringFilterKey("editor");

	public final static SizeFilterKey FILE_SIZE = new SizeFilterKey("size");

	public static final StringFilterKey STATUS = new StringFilterKey("status");

	public static final BooleanFilterKey DRY_RUN = new BooleanFilterKey("dry_run");

}
