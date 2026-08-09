package io.metaloom.cortex.node.relocate;

/**
 * What an {@code assign} node adds an asset to.
 *
 * <p>
 * Both values write a join row and nothing else. That is the whole reason this is a separate kind from {@code move}: a collection has no path and no
 * bytes, so "put this asset in the published set" and "relocate 40 GB onto another volume" are not variations of one operation, and a node that
 * silently did either depending on a parameter would be a node nobody could reason about.
 * </p>
 */
public enum AssignTarget {

	/**
	 * A collection - a purely logical, many-to-many grouping.
	 */
	COLLECTION,

	/**
	 * A library, as an organizational membership ({@code library_asset}).
	 *
	 * <p>
	 * ⚠️ Distinct from {@code move --target LIBRARY}, which relocates the bytes into the library's storage pool. This one records that the asset
	 * belongs to the library and leaves the file exactly where it is.
	 * </p>
	 */
	LIBRARY
}
