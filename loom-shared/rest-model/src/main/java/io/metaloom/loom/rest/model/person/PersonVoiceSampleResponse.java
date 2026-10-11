package io.metaloom.loom.rest.model.person;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

/**
 * One recording of a person's voice.
 *
 * <p>
 * The voice counterpart of {@link PersonImageResponse}: owned by the person rather than by any asset, so it survives deletion of the material it was
 * cut from. {@link #getPrints()} says which voice models have embedded it - a sample is only used to recognise the person in the space of a model it
 * has a usable print in.
 * </p>
 */
public class PersonVoiceSampleResponse extends AbstractCreatorEditorRestResponse<PersonVoiceSampleResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The original filename of the sample.")
	private String filename;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The mime type of the sample.")
	private String mimeType;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The size of the sample in bytes.")
	private long size;

	@JsonProperty(required = true)
	@JsonPropertyDescription("URL the sample bytes can be loaded from.")
	private String url;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether this sample is the person's hero voice sample.")
	private boolean hero;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Length of the recording in milliseconds, when it could be measured.")
	private Long durationMs;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The asset the sample was cut from, or null for an upload. Kept as provenance only: the sample does not depend on the asset.")
	private String sourceAssetUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The speaker cluster the sample was cut from, or null for an upload.")
	private String sourceClusterUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Start of the cut in the source asset, in milliseconds.")
	private Long sourceTimeFrom;

	@JsonProperty(required = false)
	@JsonPropertyDescription("End of the cut in the source asset, in milliseconds.")
	private Long sourceTimeTo;

	@JsonProperty(required = true)
	@JsonPropertyDescription("One entry per voice model the sample has been embedded with. Empty until a voiceprint node runs with a model.")
	private List<PersonPrintResponse> prints = new ArrayList<>();

	public PersonVoiceSampleResponse() {
	}

	public String getFilename() {
		return filename;
	}

	public PersonVoiceSampleResponse setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public PersonVoiceSampleResponse setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public long getSize() {
		return size;
	}

	public PersonVoiceSampleResponse setSize(long size) {
		this.size = size;
		return this;
	}

	public String getUrl() {
		return url;
	}

	public PersonVoiceSampleResponse setUrl(String url) {
		this.url = url;
		return this;
	}

	public boolean isHero() {
		return hero;
	}

	public PersonVoiceSampleResponse setHero(boolean hero) {
		this.hero = hero;
		return this;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public PersonVoiceSampleResponse setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	public String getSourceAssetUuid() {
		return sourceAssetUuid;
	}

	public PersonVoiceSampleResponse setSourceAssetUuid(String sourceAssetUuid) {
		this.sourceAssetUuid = sourceAssetUuid;
		return this;
	}

	public String getSourceClusterUuid() {
		return sourceClusterUuid;
	}

	public PersonVoiceSampleResponse setSourceClusterUuid(String sourceClusterUuid) {
		this.sourceClusterUuid = sourceClusterUuid;
		return this;
	}

	public Long getSourceTimeFrom() {
		return sourceTimeFrom;
	}

	public PersonVoiceSampleResponse setSourceTimeFrom(Long sourceTimeFrom) {
		this.sourceTimeFrom = sourceTimeFrom;
		return this;
	}

	public Long getSourceTimeTo() {
		return sourceTimeTo;
	}

	public PersonVoiceSampleResponse setSourceTimeTo(Long sourceTimeTo) {
		this.sourceTimeTo = sourceTimeTo;
		return this;
	}

	public List<PersonPrintResponse> getPrints() {
		return prints;
	}

	public PersonVoiceSampleResponse setPrints(List<PersonPrintResponse> prints) {
		this.prints = prints;
		return this;
	}

	@Override
	public PersonVoiceSampleResponse self() {
		return this;
	}
}
