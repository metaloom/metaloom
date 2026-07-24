package io.metaloom.loom.db.model.asset;

/**
 * Perceptual fingerprint component of an asset, one component per sector.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, algorithm, sector_index)</code>. The table is indexed by
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
	 * Return which sector of a multi-sector fingerprint this is; 0 for whole-asset fingerprints.
	 */
	int getSectorIndex();

	AssetFingerprintComp setSectorIndex(int sectorIndex);

	/**
	 * Return the start of the window this sector covers, in milliseconds.
	 */
	Long getTimeFrom();

	AssetFingerprintComp setTimeFrom(Long timeFrom);

	/**
	 * Return the end of the window this sector covers, in milliseconds.
	 */
	Long getTimeTo();

	AssetFingerprintComp setTimeTo(Long timeTo);

	/**
	 * Return the fingerprint value, hex or base64 encoded.
	 */
	String getFingerprint();

	AssetFingerprintComp setFingerprint(String fingerprint);
}
