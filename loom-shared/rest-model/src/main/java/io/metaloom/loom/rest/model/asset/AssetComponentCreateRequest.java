package io.metaloom.loom.rest.model.asset;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.JsonComponentInfo;
import io.metaloom.loom.rest.model.asset.info.TranscriptInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;
import io.vertx.core.json.JsonObject;

/**
 * Request model for creating an asset component. The {@code type} field determines which type-specific
 * info object is expected.
 *
 * <p>
 * The endpoint <b>upserts</b>: every component table carries a
 * {@code UNIQUE (asset_uuid, node_kind, &lt;discriminators&gt;)} key, and the discriminators are the
 * fields below - {@link #method} / {@link #timeFrom} for geo, {@link #streamIndex} for the media
 * types, {@link #pageNumber} for documents, and the schema type plus variant for JSON. Posting the
 * same identity twice replaces the row rather than failing, which is what a node re-run needs.
 * </p>
 */
public class AssetComponentCreateRequest implements RestRequestModel {

	private AssetComponentType type;
	private String source;

	@JsonPropertyDescription("Graph-local id of the node instance that produced this component.")
	private String nodeId;

	@JsonPropertyDescription("Model or algorithm version of the producer, e.g. 'metadata/1'.")
	private String producerVersion;

	@JsonPropertyDescription("Extraction confidence in [0,1], where the producer reports one.")
	private Float confidence;

	@JsonPropertyDescription("Free-form producer detail stored alongside the component.")
	private JsonObject meta;

	@JsonPropertyDescription("Geo discriminator: how the position was derived - exif, xmp, sidecar, gps-track, llm, manual.")
	private String method;

	@JsonPropertyDescription("Geo discriminator: millisecond offset into the media; 0 for stills.")
	private Long timeFrom;

	@JsonPropertyDescription("Image/video/audio discriminator: which stream within the container this describes. 0 for single-stream media.")
	private Integer streamIndex;

	@JsonPropertyDescription("Document discriminator: the page this component describes; 0 means the whole document.")
	private Integer pageNumber;

	private GeoLocationInfo geo;
	private ImageInfo image;
	private VideoInfo video;
	private AudioInfo audio;
	private DocumentInfo document;
	private TranscriptInfo transcript;
	private JsonComponentInfo json;

	public AssetComponentType getType() {
		return type;
	}

	public AssetComponentCreateRequest setType(AssetComponentType type) {
		this.type = type;
		return this;
	}

	public String getSource() {
		return source;
	}

	public AssetComponentCreateRequest setSource(String source) {
		this.source = source;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public AssetComponentCreateRequest setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public AssetComponentCreateRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	public Float getConfidence() {
		return confidence;
	}

	public AssetComponentCreateRequest setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	public JsonObject getMeta() {
		return meta;
	}

	public AssetComponentCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	public String getMethod() {
		return method;
	}

	public AssetComponentCreateRequest setMethod(String method) {
		this.method = method;
		return this;
	}

	public Long getTimeFrom() {
		return timeFrom;
	}

	public AssetComponentCreateRequest setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	public Integer getStreamIndex() {
		return streamIndex;
	}

	public AssetComponentCreateRequest setStreamIndex(Integer streamIndex) {
		this.streamIndex = streamIndex;
		return this;
	}

	public Integer getPageNumber() {
		return pageNumber;
	}

	public AssetComponentCreateRequest setPageNumber(Integer pageNumber) {
		this.pageNumber = pageNumber;
		return this;
	}

	public GeoLocationInfo getGeo() {
		return geo;
	}

	public AssetComponentCreateRequest setGeo(GeoLocationInfo geo) {
		this.geo = geo;
		return this;
	}

	public ImageInfo getImage() {
		return image;
	}

	public AssetComponentCreateRequest setImage(ImageInfo image) {
		this.image = image;
		return this;
	}

	public VideoInfo getVideo() {
		return video;
	}

	public AssetComponentCreateRequest setVideo(VideoInfo video) {
		this.video = video;
		return this;
	}

	public AudioInfo getAudio() {
		return audio;
	}

	public AssetComponentCreateRequest setAudio(AudioInfo audio) {
		this.audio = audio;
		return this;
	}

	public DocumentInfo getDocument() {
		return document;
	}

	public AssetComponentCreateRequest setDocument(DocumentInfo document) {
		this.document = document;
		return this;
	}

	public TranscriptInfo getTranscript() {
		return transcript;
	}

	public AssetComponentCreateRequest setTranscript(TranscriptInfo transcript) {
		this.transcript = transcript;
		return this;
	}

	public JsonComponentInfo getJson() {
		return json;
	}

	public AssetComponentCreateRequest setJson(JsonComponentInfo json) {
		this.json = json;
		return this;
	}
}
