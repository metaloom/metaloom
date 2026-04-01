package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetGeoComp;

public class AssetGeoCompImpl extends AbstractEditableElement<AssetGeoComp> implements AssetGeoComp {

	private UUID assetUuid;
	private String source;
	private Double geoLon;
	private Double geoLat;
	private String geoAlias;

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AssetGeoComp setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public AssetGeoComp setSource(String source) {
		this.source = source;
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
}
