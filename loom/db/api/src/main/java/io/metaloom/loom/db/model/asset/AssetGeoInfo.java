package io.metaloom.loom.db.model.asset;

public interface AssetGeoInfo {

	Double getGeoLon();

	Asset setGeoLon(Double lon);

	Double getGeoLat();

	Asset setGeoLat(Double lat);

	String getGeoAlias();

	Asset setGeoAlias(String geoAlias);

}