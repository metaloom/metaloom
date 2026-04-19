package io.metaloom.loom.rest.model.asset;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.JsonComponentInfo;
import io.metaloom.loom.rest.model.asset.info.TranscriptInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;

/**
 * Request model for creating an asset component. The {@code type} field determines which type-specific
 * info object is expected.
 */
public class AssetComponentCreateRequest implements RestRequestModel {

	private AssetComponentType type;
	private String source;

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
