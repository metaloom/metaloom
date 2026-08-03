package io.metaloom.cortex.node.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Layer 2: the canonical envelope, and the payload of the {@code metadata} JSON component.
 *
 * <p>
 * One asset produces one of these, no matter how many standards its file happens to speak. It is
 * the only shape a consumer has to learn, and its contract is:
 * </p>
 *
 * <ul>
 * <li><b>{@code v} is mandatory</b> and bumps when the <em>meaning</em> of a field changes. A reader
 * must tolerate unknown keys and missing blocks.</li>
 * <li><b>Absent is not empty.</b> A field the file did not carry is omitted, never {@code ""}.</li>
 * <li><b>Types are normalised.</b> Dates carry an offset only when the file stated one, exposure is
 * seconds as a number, coordinates are signed decimal degrees.</li>
 * <li><b>{@code dc.creator}, {@code dc.subject}, {@code dc.contributor} and {@code dc.relation} are
 * always arrays</b>; everything else in {@code dc} is scalar.</li>
 * <li><b>{@code raw} is opt-in and capped.</b> Maker notes alone can run to tens of thousands of
 * keys.</li>
 * </ul>
 *
 * <p>
 * {@link #getProvenance()} names which raw key won each canonical field. It is what makes a mapping
 * bug fixable by re-normalising instead of re-reading every asset in the library, and it is why the
 * lossy canonical form is safe to keep.
 * </p>
 *
 * <p>
 * An <b>empty envelope is a success</b>, not a failure and not a skip: a stripped PNG genuinely
 * carries no metadata, and recording that fact is a result.
 * </p>
 */
public class AssetMetadata {

	/** Envelope version. Bump when the meaning of a field changes, never for an added one. */
	public static final int VERSION = 1;

	private final DcBlock dc = new DcBlock();

	private final RightsBlock rights = new RightsBlock();

	private final CaptureBlock capture = new CaptureBlock();

	/**
	 * Position readings, in time order. Zero or one for a still; a moving camera would contribute
	 * one per sample. Each becomes an {@code asset_geo_comp} row.
	 */
	private final List<GeoBlock> geo = new ArrayList<>();

	private String city;
	private String state;
	private String country;

	private final Map<String, String> provenance = new LinkedHashMap<>();

	private final Set<String> sources = new LinkedHashSet<>();

	private final Map<String, String> raw = new LinkedHashMap<>();

	public DcBlock getDc() {
		return dc;
	}

	public RightsBlock getRights() {
		return rights;
	}

	public CaptureBlock getCapture() {
		return capture;
	}

	public List<GeoBlock> getGeo() {
		return geo;
	}

	/**
	 * The first position reading, or null when the file carried no coordinate.
	 */
	public GeoBlock firstGeo() {
		return geo.isEmpty() ? null : geo.get(0);
	}

	public String getCity() {
		return city;
	}

	public AssetMetadata setCity(String city) {
		this.city = city;
		return this;
	}

	public String getState() {
		return state;
	}

	public AssetMetadata setState(String state) {
		this.state = state;
		return this;
	}

	public String getCountry() {
		return country;
	}

	public AssetMetadata setCountry(String country) {
		this.country = country;
		return this;
	}

	/**
	 * The place name IPTC gave, as a single line - the value {@code asset_geo_comp.geo_alias} takes
	 * when the <em>file itself</em> names a location. Null when it did not; this node never derives a
	 * name from a coordinate.
	 */
	public String placeLabel() {
		List<String> parts = new ArrayList<>(3);
		if (city != null) {
			parts.add(city);
		}
		if (state != null) {
			parts.add(state);
		}
		if (country != null) {
			parts.add(country);
		}
		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	/**
	 * Canonical field path (e.g. {@code dc.title}) to the raw key that supplied it.
	 */
	public Map<String, String> getProvenance() {
		return provenance;
	}

	public AssetMetadata recordProvenance(String field, String rawKey) {
		if (rawKey != null) {
			provenance.put(field, rawKey);
		}
		return this;
	}

	/**
	 * The standards that contributed at least one value: {@code exif}, {@code iptc}, {@code xmp},
	 * {@code sidecar}, {@code container}.
	 */
	public Set<String> getSources() {
		return sources;
	}

	public Map<String, String> getRaw() {
		return raw;
	}

	/**
	 * True when the file said nothing about itself beyond what the filesystem already knew. Still a
	 * successful run.
	 */
	public boolean isEmpty() {
		return dc.isEmpty() && rights.isEmpty() && capture.isEmpty() && geo.isEmpty() && placeLabel() == null;
	}

	/**
	 * The {@code text/plain} output: the authored prose, one field per line. This is what feeds
	 * {@code translate}, {@code sentiment}, {@code llm} and every other text consumer - which is why
	 * it carries title, description, keywords and creator and nothing numeric.
	 */
	public String toText() {
		return textFrom(toJson());
	}

	/**
	 * The same text, derived from a <em>serialised</em> envelope.
	 *
	 * <p>
	 * The node emits from this rather than from the object, because its skip cache stores the encoded
	 * envelope: deriving the ports from one shape on a fresh run and the other on a cache hit is how
	 * the two quietly drift apart. {@link #toText()} delegates here so there is still only one rule.
	 * </p>
	 */
	public static String textFrom(JsonObject envelope) {
		JsonObject dc = envelope.getJsonObject("dc", new JsonObject());
		List<String> lines = new ArrayList<>();
		addIfPresent(lines, dc.getString("title"));
		addIfPresent(lines, dc.getString("description"));
		addJoined(lines, dc.getJsonArray("subject"));
		addJoined(lines, dc.getJsonArray("creator"));
		addIfPresent(lines, dc.getString("coverage"));
		return String.join("\n", lines);
	}

	/**
	 * The {@code geo} output port payload - coordinates only, for a downstream geocoder - or null
	 * when the file carried no coordinate and the port must stay unwritten.
	 */
	public static JsonObject geoPortFrom(JsonObject envelope) {
		JsonObject geo = envelope.getJsonObject("geo");
		if (geo == null || !geo.containsKey("lat")) {
			return null;
		}
		JsonObject port = new JsonObject()
			.put("lat", geo.getDouble("lat"))
			.put("lon", geo.getDouble("lon"));
		Envelopes.putIfPresent(port, "altitudeM", geo.getDouble("altitudeM"));
		Envelopes.putIfPresent(port, "accuracyM", geo.getDouble("accuracyM"));
		return port;
	}

	private static void addIfPresent(List<String> lines, String value) {
		if (value != null && !value.isBlank()) {
			lines.add(value);
		}
	}

	private static void addJoined(List<String> lines, JsonArray values) {
		if (values == null || values.isEmpty()) {
			return;
		}
		List<String> parts = new ArrayList<>(values.size());
		values.forEach(entry -> parts.add(String.valueOf(entry)));
		lines.add(String.join(", ", parts));
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject().put("v", VERSION);
		json.put("dc", dc.toJson());
		Envelopes.putIfPresent(json, "rights", rights.toJson());
		Envelopes.putIfPresent(json, "capture", capture.toJson());

		// The envelope carries the representative reading; a track's remaining samples are rows in
		// asset_geo_comp, discoverable by (asset, node_kind = 'metadata').
		JsonObject geoJson = geo.isEmpty() ? new JsonObject() : geo.get(0).toJson();
		JsonObject place = new JsonObject();
		Envelopes.putIfPresent(place, "city", city);
		Envelopes.putIfPresent(place, "state", state);
		Envelopes.putIfPresent(place, "country", country);
		Envelopes.putIfPresent(geoJson, "place", place);
		if (geo.size() > 1) {
			geoJson.put("sampleCount", geo.size());
		}
		Envelopes.putIfPresent(json, "geo", geoJson);

		if (!provenance.isEmpty()) {
			JsonObject prov = new JsonObject();
			provenance.forEach(prov::put);
			json.put("provenance", prov);
		}
		json.put("sources", new JsonArray(List.copyOf(sources)));
		if (!raw.isEmpty()) {
			JsonObject rawJson = new JsonObject();
			raw.forEach(rawJson::put);
			json.put("raw", rawJson);
		}
		return json;
	}
}
