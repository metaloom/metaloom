package io.metaloom.loom.rest.model.asset;

import java.util.UUID;

import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.JsonComponentInfo;
import io.metaloom.loom.rest.model.asset.info.TranscriptInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

/**
 * REST response for a single asset component. The populated info field depends on the component {@link #type}.
 */
public class AssetComponentResponse extends AbstractCreatorEditorRestResponse<AssetComponentResponse> {

	private AssetComponentType type;
	private UUID assetUuid;
	private String source;

	private String nodeId;
	private String producerVersion;
	private Float confidence;

	/** Geo discriminator: how the position was derived. */
	private String method;

	/** Geo discriminator: millisecond offset into the media. */
	private Long timeFrom;

	/** Image/video/audio/transcript discriminator. */
	private Integer streamIndex;

	/** Document discriminator. */
	private Integer pageNumber;

	private GeoLocationInfo geo;
	private ImageInfo image;
	private VideoInfo video;
	private AudioInfo audio;
	private DocumentInfo document;
	private TranscriptInfo transcript;
	private JsonComponentInfo json;

	@Override
	public AssetComponentResponse self() {
		return this;
	}

	public AssetComponentType getType() {
		return type;
	}

	public AssetComponentResponse setType(AssetComponentType type) {
		this.type = type;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public AssetComponentResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getSource() {
		return source;
	}

	public AssetComponentResponse setSource(String source) {
		this.source = source;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public AssetComponentResponse setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public AssetComponentResponse setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	public Float getConfidence() {
		return confidence;
	}

	public AssetComponentResponse setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	public String getMethod() {
		return method;
	}

	public AssetComponentResponse setMethod(String method) {
		this.method = method;
		return this;
	}

	public Long getTimeFrom() {
		return timeFrom;
	}

	public AssetComponentResponse setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	public Integer getStreamIndex() {
		return streamIndex;
	}

	public AssetComponentResponse setStreamIndex(Integer streamIndex) {
		this.streamIndex = streamIndex;
		return this;
	}

	public Integer getPageNumber() {
		return pageNumber;
	}

	public AssetComponentResponse setPageNumber(Integer pageNumber) {
		this.pageNumber = pageNumber;
		return this;
	}

	public GeoLocationInfo getGeo() {
		return geo;
	}

	public AssetComponentResponse setGeo(GeoLocationInfo geo) {
		this.geo = geo;
		return this;
	}

	public ImageInfo getImage() {
		return image;
	}

	public AssetComponentResponse setImage(ImageInfo image) {
		this.image = image;
		return this;
	}

	public VideoInfo getVideo() {
		return video;
	}

	public AssetComponentResponse setVideo(VideoInfo video) {
		this.video = video;
		return this;
	}

	public AudioInfo getAudio() {
		return audio;
	}

	public AssetComponentResponse setAudio(AudioInfo audio) {
		this.audio = audio;
		return this;
	}

	public DocumentInfo getDocument() {
		return document;
	}

	public AssetComponentResponse setDocument(DocumentInfo document) {
		this.document = document;
		return this;
	}

	public TranscriptInfo getTranscript() {
		return transcript;
	}

	public AssetComponentResponse setTranscript(TranscriptInfo transcript) {
		this.transcript = transcript;
		return this;
	}

	public JsonComponentInfo getJson() {
		return json;
	}

	public AssetComponentResponse setJson(JsonComponentInfo json) {
		this.json = json;
		return this;
	}
}
