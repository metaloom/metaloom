package io.metaloom.cortex.node.filter;

/**
 * Which tags a {@link FilterBy#TAG} filter counts.
 *
 * <p>
 * A node option rather than a syntax in the match column. {@code manual:hero} would be a second
 * grammar inside a field that already has one, and the choice is nearly always made once for the
 * whole node — "route on what people decided" or "route on what the pipeline guessed" — rather than
 * per bucket.
 * </p>
 */
public enum TagSource {

	/** Every tag on the asset, whoever attached it. */
	ANY,

	/**
	 * Only tags a person attached.
	 *
	 * <p>
	 * An absent {@code nodeKind} counts as manual, because {@code tag_asset.node_kind} defaults to
	 * {@code 'manual'} deliberately: a machine tag mislabelled human is merely not filtered out,
	 * while a human tag mislabelled machine could be deleted by a reconciling node.
	 * </p>
	 */
	MANUAL,

	/** Only tags a pipeline node attached. */
	MACHINE
}
