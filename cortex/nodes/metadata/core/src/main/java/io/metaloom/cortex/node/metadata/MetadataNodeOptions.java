package io.metaloom.cortex.node.metadata;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.loom.nodes.spec.ParameterType;

/**
 * Options for the {@link MetadataNode}.
 *
 * <p>
 * These arrive from the <b>pipeline node definition</b>, not from the worker YAML - see
 * {@link MetadataNode#configure(io.vertx.core.json.JsonObject)}. That matters most for
 * {@link #gpsPolicy}: a public-library pipeline and an internal-archive pipeline must be able to
 * treat the same worker's coordinates differently, which a per-worker setting cannot express.
 * </p>
 */
public class MetadataNodeOptions extends AbstractNodeOptions<MetadataNodeOptions> {

	public static final String KEY = "metadata";

	/** Upper bound on {@link #gpsRoundDecimals}: EXIF itself does not carry more than this usefully. */
	public static final int MAX_GPS_DECIMALS = 6;

	@ParamDoc(label = "Include Raw Metadata",
		description = "Store every key the file carried, unmapped. Off by default: maker notes can carry serial numbers and owner names")
	private boolean includeRaw = false;

	@ParamDoc(label = "Raw Key Limit", description = "Maximum number of raw entries stored", min = "1")
	private int rawMaxKeys = 500;

	@ParamDoc(label = "Raw Value Limit", description = "Longer raw values are truncated", min = "1")
	private int rawMaxValueBytes = 4096;

	@ParamDoc(label = "Read XMP Sidecar", description = "Merge a companion .xmp file when one sits next to the media")
	private boolean readXmpSidecar = true;

	@ParamDoc(label = "Store Position", description = "Write the position to the asset's geo component")
	private boolean writeGeoComponent = true;

	@ParamDoc(label = "Position Sample Limit", description = "Upper bound on the position readings kept per asset", min = "1")
	private int gpsTrackMaxSamples = 1000;

	@ParamDoc(label = "Position Policy", description = "KEEP the exact coordinate, ROUND it, or DROP it entirely",
		type = ParameterType.STRING)
	private GpsPolicy gpsPolicy = GpsPolicy.KEEP;

	@ParamDoc(label = "Position Rounding", description = "Decimal places kept when rounding; 2 is roughly 1.1 km",
		min = "0", max = "6")
	private int gpsRoundDecimals = 2;

	@ParamDoc(label = "Emit Text", description = "Produce the plain-text output for downstream text nodes")
	private boolean emitText = true;

	@ParamDoc(label = "Detect Licence", description = "Recognise well-known licence URLs and record their identifier")
	private boolean licenseDetection = true;

	@ParamDoc(label = "Date Fallback", description = "NONE, or FILESYSTEM to fall back to the file's modification time",
		type = ParameterType.STRING)
	private DateFallback dateFallback = DateFallback.NONE;

	@ParamDoc(label = "Excluded Keys", description = "Metadata keys to drop before anything else reads them",
		type = ParameterType.STRING)
	private List<String> excludeKeys = new ArrayList<>();

	@Override
	protected MetadataNodeOptions self() {
		return this;
	}

	/**
	 * Whether to write the lossless {@code raw} block into the envelope. Off by default: maker notes
	 * are the least predictable surface in the whole format zoo and can carry serial numbers and, in
	 * some camera generations, owner names. Opting in should be a decision.
	 */
	public boolean isIncludeRaw() {
		return includeRaw;
	}

	public MetadataNodeOptions setIncludeRaw(boolean includeRaw) {
		this.includeRaw = includeRaw;
		return this;
	}

	public int getRawMaxKeys() {
		return rawMaxKeys;
	}

	public MetadataNodeOptions setRawMaxKeys(int rawMaxKeys) {
		this.rawMaxKeys = rawMaxKeys;
		return this;
	}

	public int getRawMaxValueBytes() {
		return rawMaxValueBytes;
	}

	public MetadataNodeOptions setRawMaxValueBytes(int rawMaxValueBytes) {
		this.rawMaxValueBytes = rawMaxValueBytes;
		return this;
	}

	/**
	 * Whether to read a {@code <asset>.xmp} sidecar sitting next to the media. A missing sidecar is
	 * normal, never a failure - an object-store-backed media item has no sibling file at all.
	 */
	public boolean isReadXmpSidecar() {
		return readXmpSidecar;
	}

	public MetadataNodeOptions setReadXmpSidecar(boolean readXmpSidecar) {
		this.readXmpSidecar = readXmpSidecar;
		return this;
	}

	public boolean isWriteGeoComponent() {
		return writeGeoComponent;
	}

	public MetadataNodeOptions setWriteGeoComponent(boolean writeGeoComponent) {
		this.writeGeoComponent = writeGeoComponent;
		return this;
	}

	public int getGpsTrackMaxSamples() {
		return gpsTrackMaxSamples;
	}

	public MetadataNodeOptions setGpsTrackMaxSamples(int gpsTrackMaxSamples) {
		this.gpsTrackMaxSamples = gpsTrackMaxSamples;
		return this;
	}

	public GpsPolicy getGpsPolicy() {
		return gpsPolicy;
	}

	public MetadataNodeOptions setGpsPolicy(GpsPolicy gpsPolicy) {
		this.gpsPolicy = gpsPolicy;
		return this;
	}

	public int getGpsRoundDecimals() {
		return gpsRoundDecimals;
	}

	public MetadataNodeOptions setGpsRoundDecimals(int gpsRoundDecimals) {
		this.gpsRoundDecimals = gpsRoundDecimals;
		return this;
	}

	public boolean isEmitText() {
		return emitText;
	}

	public MetadataNodeOptions setEmitText(boolean emitText) {
		this.emitText = emitText;
		return this;
	}

	public boolean isLicenseDetection() {
		return licenseDetection;
	}

	public MetadataNodeOptions setLicenseDetection(boolean licenseDetection) {
		this.licenseDetection = licenseDetection;
		return this;
	}

	public DateFallback getDateFallback() {
		return dateFallback;
	}

	public MetadataNodeOptions setDateFallback(DateFallback dateFallback) {
		this.dateFallback = dateFallback;
		return this;
	}

	/**
	 * Fully qualified raw keys to drop before anything else runs - the deny list for a vendor tag a
	 * collection must not ingest.
	 */
	public List<String> getExcludeKeys() {
		return excludeKeys;
	}

	public MetadataNodeOptions setExcludeKeys(List<String> excludeKeys) {
		this.excludeKeys = excludeKeys == null ? new ArrayList<>() : new ArrayList<>(excludeKeys);
		return this;
	}

	/**
	 * A stable digest of every option that changes the output.
	 *
	 * <p>
	 * Part of the node's cache key. Two differently configured instances of this node can appear in
	 * one graph - a public branch rounding coordinates and an archive branch keeping them - and
	 * keying the cache on the media path alone would serve the first one's envelope to the second.
	 * </p>
	 */
	public String digest() {
		return new StringBuilder()
			.append(includeRaw).append('|')
			.append(rawMaxKeys).append('|')
			.append(rawMaxValueBytes).append('|')
			.append(readXmpSidecar).append('|')
			.append(writeGeoComponent).append('|')
			.append(gpsTrackMaxSamples).append('|')
			.append(gpsPolicy).append('|')
			.append(gpsRoundDecimals).append('|')
			.append(emitText).append('|')
			.append(licenseDetection).append('|')
			.append(dateFallback).append('|')
			.append(String.join(",", excludeKeys))
			.toString();
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());

		if (rawMaxKeys <= 0) {
			errors.add("rawMaxKeys must be positive, got " + rawMaxKeys);
		}
		if (rawMaxValueBytes <= 0) {
			errors.add("rawMaxValueBytes must be positive, got " + rawMaxValueBytes);
		}
		if (gpsTrackMaxSamples <= 0) {
			errors.add("gpsTrackMaxSamples must be positive, got " + gpsTrackMaxSamples);
		}
		if (gpsPolicy == null) {
			errors.add("gpsPolicy must not be null");
		}
		if (gpsRoundDecimals < 0 || gpsRoundDecimals > MAX_GPS_DECIMALS) {
			errors.add("gpsRoundDecimals must be between 0 and " + MAX_GPS_DECIMALS + ", got " + gpsRoundDecimals);
		}
		if (dateFallback == null) {
			errors.add("dateFallback must not be null");
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
