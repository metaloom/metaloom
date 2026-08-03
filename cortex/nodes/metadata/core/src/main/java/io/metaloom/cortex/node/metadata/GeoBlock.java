package io.metaloom.cortex.node.metadata;

import io.vertx.core.json.JsonObject;

/**
 * One position reading, and the {@code asset_geo_comp} row it becomes.
 *
 * <p>
 * A still image yields exactly one of these. The type is nonetheless a <em>sample</em> rather than a
 * singleton because {@code asset_geo_comp} is keyed by
 * {@code (asset, node_kind, method, time_from)} precisely so a moving camera can contribute one row
 * per reading, and modelling it as a scalar here would have to be undone the day a track extractor
 * lands. {@link #timeFromMs} is the millisecond offset into the media, 0 for a still.
 * </p>
 *
 * <p>
 * {@link #method} is the <b>source</b> the coordinate came from - {@code exif}, {@code xmp},
 * {@code sidecar}, {@code gps-track} - never the file format. It is part of the row's identity, so
 * writing {@code jpeg} there would make an EXIF reading and an XMP reading of the same photo
 * indistinguishable.
 * </p>
 *
 * <p>
 * There is deliberately no place name here. IPTC's {@code City} / {@code Country} are <em>names</em>,
 * not coordinates; they live on {@link AssetMetadata} and are never converted into a
 * {@link #lat}/{@link #lon}. Turning a name into a coordinate is geocoding, and belongs to a
 * different node.
 * </p>
 */
public class GeoBlock {

	private double lat;
	private double lon;
	private String method;
	private long timeFromMs;
	private Double altitudeM;
	private Float accuracyM;
	private Double directionDeg;
	private String timestamp;

	public GeoBlock(double lat, double lon, String method) {
		this.lat = lat;
		this.lon = lon;
		this.method = method;
	}

	public double getLat() {
		return lat;
	}

	public GeoBlock setLat(double lat) {
		this.lat = lat;
		return this;
	}

	public double getLon() {
		return lon;
	}

	public GeoBlock setLon(double lon) {
		this.lon = lon;
		return this;
	}

	public String getMethod() {
		return method;
	}

	public GeoBlock setMethod(String method) {
		this.method = method;
		return this;
	}

	public long getTimeFromMs() {
		return timeFromMs;
	}

	public GeoBlock setTimeFromMs(long timeFromMs) {
		this.timeFromMs = timeFromMs;
		return this;
	}

	public Double getAltitudeM() {
		return altitudeM;
	}

	public GeoBlock setAltitudeM(Double altitudeM) {
		this.altitudeM = altitudeM;
		return this;
	}

	public Float getAccuracyM() {
		return accuracyM;
	}

	public GeoBlock setAccuracyM(Float accuracyM) {
		this.accuracyM = accuracyM;
		return this;
	}

	public Double getDirectionDeg() {
		return directionDeg;
	}

	public GeoBlock setDirectionDeg(Double directionDeg) {
		this.directionDeg = directionDeg;
		return this;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public GeoBlock setTimestamp(String timestamp) {
		this.timestamp = timestamp;
		return this;
	}

	/**
	 * The {@code meta} column of the geo component: the readings that have no column of their own.
	 */
	public JsonObject toComponentMeta() {
		JsonObject meta = new JsonObject();
		Envelopes.putIfPresent(meta, "altitudeM", altitudeM);
		Envelopes.putIfPresent(meta, "directionDeg", directionDeg);
		Envelopes.putIfPresent(meta, "gpsTimestamp", timestamp);
		if (timeFromMs != 0) {
			meta.put("timeFromMs", timeFromMs);
		}
		return meta;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject()
			.put("lat", lat)
			.put("lon", lon)
			.put("method", method);
		Envelopes.putIfPresent(json, "altitudeM", altitudeM);
		Envelopes.putIfPresent(json, "accuracyM", accuracyM);
		Envelopes.putIfPresent(json, "directionDeg", directionDeg);
		Envelopes.putIfPresent(json, "timestamp", timestamp);
		return json;
	}

}
