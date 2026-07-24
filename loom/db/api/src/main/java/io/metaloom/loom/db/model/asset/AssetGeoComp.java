package io.metaloom.loom.db.model.asset;

/**
 * Geo location component of an asset.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, method, time_from)</code>. A photo tagged by EXIF and guessed by an LLM has two components; a drone video
 * carries a whole GPS track as one component per time offset.
 * </p>
 */
public interface AssetGeoComp extends AssetComponent<AssetGeoComp> {

	/**
	 * Return how the position was derived: exif, xmp, gps-track, llm, manual. Never null - unknown is the empty string.
	 */
	String getMethod();

	AssetGeoComp setMethod(String method);

	/**
	 * Return the millisecond offset into the media this position was recorded at; 0 for stills.
	 */
	long getTimeFrom();

	AssetGeoComp setTimeFrom(long timeFrom);

	Double getGeoLon();

	AssetGeoComp setGeoLon(Double lon);

	Double getGeoLat();

	AssetGeoComp setGeoLat(Double lat);

	String getGeoAlias();

	AssetGeoComp setGeoAlias(String geoAlias);

	/**
	 * Return the reported accuracy in meters, when known.
	 */
	Float getAccuracyM();

	AssetGeoComp setAccuracyM(Float accuracyM);
}
