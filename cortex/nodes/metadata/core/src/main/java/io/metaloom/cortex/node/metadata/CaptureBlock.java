package io.metaloom.cortex.node.metadata;

import io.vertx.core.json.JsonObject;

/**
 * What the camera recorded about the exposure.
 *
 * <p>
 * The units are normalised, not passed through: {@link #exposureTime} is seconds as a number, not
 * {@code "1/250"}; {@link #fNumber} is a number, not {@code "f/8.0"}; {@link #flash} is a real
 * boolean. Anything a downstream filter would have to re-parse defeats the point of normalising.
 * </p>
 *
 * <p>
 * {@link #dateTimeOriginal} is a local-time string <b>without an offset</b> unless the file carried
 * one (EXIF 2.31's {@code OffsetTimeOriginal}). EXIF's own {@code DateTimeOriginal} has no timezone,
 * and assuming UTC would shift an evening photo into the next day and quietly corrupt every
 * date-range query over the catalogue.
 * </p>
 */
public class CaptureBlock {

	private String make;
	private String model;
	private String lens;
	private String software;
	private String dateTimeOriginal;
	private Double exposureTime;
	private Double fNumber;
	private Integer iso;
	private Double focalLength;
	private Double focalLength35;
	private Boolean flash;
	private Integer orientation;
	private String colorSpace;
	private String whiteBalance;

	public String getMake() {
		return make;
	}

	public CaptureBlock setMake(String make) {
		this.make = make;
		return this;
	}

	public String getModel() {
		return model;
	}

	public CaptureBlock setModel(String model) {
		this.model = model;
		return this;
	}

	public String getLens() {
		return lens;
	}

	public CaptureBlock setLens(String lens) {
		this.lens = lens;
		return this;
	}

	public String getSoftware() {
		return software;
	}

	public CaptureBlock setSoftware(String software) {
		this.software = software;
		return this;
	}

	public String getDateTimeOriginal() {
		return dateTimeOriginal;
	}

	public CaptureBlock setDateTimeOriginal(String dateTimeOriginal) {
		this.dateTimeOriginal = dateTimeOriginal;
		return this;
	}

	public Double getExposureTime() {
		return exposureTime;
	}

	public CaptureBlock setExposureTime(Double exposureTime) {
		this.exposureTime = exposureTime;
		return this;
	}

	public Double getFNumber() {
		return fNumber;
	}

	public CaptureBlock setFNumber(Double fNumber) {
		this.fNumber = fNumber;
		return this;
	}

	public Integer getIso() {
		return iso;
	}

	public CaptureBlock setIso(Integer iso) {
		this.iso = iso;
		return this;
	}

	public Double getFocalLength() {
		return focalLength;
	}

	public CaptureBlock setFocalLength(Double focalLength) {
		this.focalLength = focalLength;
		return this;
	}

	public Double getFocalLength35() {
		return focalLength35;
	}

	public CaptureBlock setFocalLength35(Double focalLength35) {
		this.focalLength35 = focalLength35;
		return this;
	}

	public Boolean getFlash() {
		return flash;
	}

	public CaptureBlock setFlash(Boolean flash) {
		this.flash = flash;
		return this;
	}

	public Integer getOrientation() {
		return orientation;
	}

	public CaptureBlock setOrientation(Integer orientation) {
		this.orientation = orientation;
		return this;
	}

	public String getColorSpace() {
		return colorSpace;
	}

	public CaptureBlock setColorSpace(String colorSpace) {
		this.colorSpace = colorSpace;
		return this;
	}

	public String getWhiteBalance() {
		return whiteBalance;
	}

	public CaptureBlock setWhiteBalance(String whiteBalance) {
		this.whiteBalance = whiteBalance;
		return this;
	}

	public boolean isEmpty() {
		return make == null && model == null && lens == null && software == null && dateTimeOriginal == null
			&& exposureTime == null && fNumber == null && iso == null && focalLength == null
			&& focalLength35 == null && flash == null && orientation == null && colorSpace == null
			&& whiteBalance == null;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		Envelopes.putIfPresent(json, "make", make);
		Envelopes.putIfPresent(json, "model", model);
		Envelopes.putIfPresent(json, "lens", lens);
		Envelopes.putIfPresent(json, "software", software);
		Envelopes.putIfPresent(json, "dateTimeOriginal", dateTimeOriginal);
		Envelopes.putIfPresent(json, "exposureTime", exposureTime);
		Envelopes.putIfPresent(json, "fNumber", fNumber);
		Envelopes.putIfPresent(json, "iso", iso);
		Envelopes.putIfPresent(json, "focalLength", focalLength);
		Envelopes.putIfPresent(json, "focalLength35", focalLength35);
		Envelopes.putIfPresent(json, "flash", flash);
		Envelopes.putIfPresent(json, "orientation", orientation);
		Envelopes.putIfPresent(json, "colorSpace", colorSpace);
		Envelopes.putIfPresent(json, "whiteBalance", whiteBalance);
		return json;
	}
}
