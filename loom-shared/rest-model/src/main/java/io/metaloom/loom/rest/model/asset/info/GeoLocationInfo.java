package io.metaloom.loom.rest.model.asset.info;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Longitude and latitude information of the asset. (e.g. extracted Photo GPS information)
 */
public class GeoLocationInfo implements RestModel {

	@JsonPropertyDescription("Source of the geo information (e.g. exif, user input)")
	private String source;

	@JsonPropertyDescription("The longitude in decimal degrees.")
	private Double lon;

	@JsonPropertyDescription("The latitude in decimal degrees.")
	private Double lat;

	@JsonPropertyDescription("Alias name for the location.")
	private String alias;

	public String getSource() {
		return source;
	}

	public GeoLocationInfo setSource(String source) {
		this.source = source;
		return this;
	}

	public Double getLon() {
		return lon;
	}

	public GeoLocationInfo setLon(Double lon) {
		this.lon = lon;
		return this;
	}

	public Double getLat() {
		return lat;
	}

	public GeoLocationInfo setLat(Double lat) {
		this.lat = lat;
		return this;
	}

	public String getAlias() {
		return alias;
	}

	public GeoLocationInfo setAlias(String alias) {
		this.alias = alias;
		return this;
	}
}
