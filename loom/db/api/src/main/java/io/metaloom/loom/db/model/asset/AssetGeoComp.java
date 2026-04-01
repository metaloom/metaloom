package io.metaloom.loom.db.model.asset;

/**
 * Geo location component of an asset. Multiple geo components can exist per asset (e.g. from different sources like EXIF, user input).
 */
public interface AssetGeoComp extends AssetComponent<AssetGeoComp> {

	Double getGeoLon();

	AssetGeoComp setGeoLon(Double lon);

	Double getGeoLat();

	AssetGeoComp setGeoLat(Double lat);

	String getGeoAlias();

	AssetGeoComp setGeoAlias(String geoAlias);
}
