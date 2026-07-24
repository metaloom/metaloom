package io.metaloom.loom.db.jooq.dao.asset.comp;

import io.metaloom.loom.db.model.asset.AssetGeoComp;

public class AssetGeoCompImpl extends AbstractAssetCompImpl<AssetGeoComp> implements AssetGeoComp {

	private String method = "";
	private long timeFrom;
	private Double geoLon;
	private Double geoLat;
	private String geoAlias;
	private Float accuracyM;

	@Override
	public String getMethod() {
		return method;
	}

	@Override
	public AssetGeoComp setMethod(String method) {
		this.method = method == null ? "" : method;
		return this;
	}

	@Override
	public long getTimeFrom() {
		return timeFrom;
	}

	@Override
	public AssetGeoComp setTimeFrom(long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public Double getGeoLon() {
		return geoLon;
	}

	@Override
	public AssetGeoComp setGeoLon(Double lon) {
		this.geoLon = lon;
		return this;
	}

	@Override
	public Double getGeoLat() {
		return geoLat;
	}

	@Override
	public AssetGeoComp setGeoLat(Double lat) {
		this.geoLat = lat;
		return this;
	}

	@Override
	public String getGeoAlias() {
		return geoAlias;
	}

	@Override
	public AssetGeoComp setGeoAlias(String geoAlias) {
		this.geoAlias = geoAlias;
		return this;
	}

	@Override
	public Float getAccuracyM() {
		return accuracyM;
	}

	@Override
	public AssetGeoComp setAccuracyM(Float accuracyM) {
		this.accuracyM = accuracyM;
		return this;
	}
}
