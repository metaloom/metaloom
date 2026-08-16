package io.metaloom.loom.db.model.asset;

/**
 * Perceptual fingerprint component of an asset, one component per timeline window.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, algorithm, window_index)</code>. The table is indexed by
 * <code>(algorithm, fingerprint)</code> so "which other assets share this fingerprint" is an index scan rather than a table walk.
 * </p>
 */
public interface AssetFingerprintComp extends AssetComponent<AssetFingerprintComp> {

	/**
	 * Return the fingerprint algorithm identifier, e.g. metaloom-multisector-v1.
	 */
	String getAlgorithm();

	AssetFingerprintComp setAlgorithm(String algorithm);

	/**
	 * Return the timeline window this row covers: 0 is the whole-asset fingerprint, 1..n are windows with {@code timeFrom}/{@code timeTo} set.
	 * Unrelated to the internal sectors of the multi-sector fingerprint algorithm, which are folded into a single vector and never become rows.
	 */
	int getWindowIndex();

	AssetFingerprintComp setWindowIndex(int windowIndex);

	/**
	 * Return the start of the window this row covers, in milliseconds, or null on the whole-asset row.
	 */
	Long getTimeFrom();

	AssetFingerprintComp setTimeFrom(Long timeFrom);

	/**
	 * Return the end of the window this row covers, in milliseconds, or null on the whole-asset row.
	 */
	Long getTimeTo();

	AssetFingerprintComp setTimeTo(Long timeTo);

	/**
	 * Return the fingerprint value, hex or base64 encoded.
	 */
	String getFingerprint();

	AssetFingerprintComp setFingerprint(String fingerprint);
}
