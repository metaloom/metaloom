package io.metaloom.loom.rest.model.asset;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.ConsistencyInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.FingerprintInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.MediaInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.tag.TagReference;
import io.vertx.core.json.JsonObject;

public class AssetCreateRequest implements RestRequestModel, AssetModel<AssetCreateRequest> {

	// @JsonPropertyDescription("The specific identified kind of asset.")
	// private AssetKind kind;

	@JsonPropertyDescription("Custom meta properties for the asset.")
	private JsonObject meta;

	@JsonPropertyDescription("The GPS location of the asset.")
	private GeoLocationInfo geo;

	@JsonPropertyDescription("Timeline information on the asset.")
	private List<AnnotationResponse> timeline;

	@JsonPropertyDescription("A list of tags on the asset.")
	private List<TagReference> tags;

	// @JsonPropertyDescription("The local path of the asset. This will only be returned when the asset was created using a local path.")
	// private String localPath;

	@JsonPropertyDescription("Information about the file of the asset")
	private FileInfo file;

	@JsonPropertyDescription("Information about consistency checks on the the asset.")
	private ConsistencyInfo consistency;

	@JsonPropertyDescription("A set of different computed hashes for the asset.")
	private HashInfo hashes;

	@JsonPropertyDescription("Information about fingerprint of the asset")
	private FingerprintInfo fingerprint;

	@JsonPropertyDescription("Information about common media properties (e.g. duration, dimension)")
	private MediaInfo media;

	@JsonPropertyDescription("Information about the image component of the asset (if present)")
	private ImageInfo image;

	@JsonPropertyDescription("Information about the video component of the asset (if present)")
	private VideoInfo video;

	@JsonPropertyDescription("Information about the audio component of the asset (if present)")
	private AudioInfo audio;

	@JsonPropertyDescription("Information about the document (text) component of the asset (if present)")
	private DocumentInfo document;

	@JsonPropertyDescription("Information on embeddings")
	private List<EmbeddingCreateRequest> embeddings = new ArrayList<>();

	// private String origin;

	public AssetCreateRequest() {
	}

	// public AssetKind getKind() {
	// return kind;
	// }
	//
	// public AssetCreateRequest setKind(AssetKind kind) {
	// this.kind = kind;
	// return this;
	// }

	@Override
	public GeoLocationInfo getGeo() {
		return geo;
	}

	@Override
	public AssetCreateRequest setGeo(GeoLocationInfo geo) {
		this.geo = geo;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public AssetCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	public List<AnnotationResponse> getTimeline() {
		return timeline;
	}

	public AssetCreateRequest setTimeline(List<AnnotationResponse> timeline) {
		this.timeline = timeline;
		return this;
	}

	public List<TagReference> getTags() {
		return tags;
	}

	public AssetCreateRequest setTags(List<TagReference> tags) {
		this.tags = tags;
		return this;
	}

	@Override
	public HashInfo getHashes() {
		return hashes;
	}

	@Override
	public AssetCreateRequest setHashes(HashInfo hashes) {
		this.hashes = hashes;
		return this;
	}

	@Override
	public AssetCreateRequest setFingerprint(FingerprintInfo fingerprint) {
		this.fingerprint = fingerprint;
		return this;
	}

	@Override
	public FingerprintInfo getFingerprint() {
		return fingerprint;
	}

	public MediaInfo getMedia() {
		return media;
	}

	public AssetCreateRequest setMedia(MediaInfo media) {
		this.media = media;
		return this;
	}

	public AudioInfo getAudio() {
		return audio;
	}

	public AssetCreateRequest setAudio(AudioInfo audio) {
		this.audio = audio;
		return this;
	}

	public ImageInfo getImage() {
		return image;
	}

	public AssetCreateRequest setImage(ImageInfo image) {
		this.image = image;
		return this;
	}

	public VideoInfo getVideo() {
		return video;
	}

	public AssetCreateRequest setVideo(VideoInfo video) {
		this.video = video;
		return this;
	}

	public DocumentInfo getDocument() {
		return document;
	}

	public AssetCreateRequest setDocument(DocumentInfo document) {
		this.document = document;
		return this;
	}

	// public String getOrigin() {
	// return origin;
	// }

	// public AssetCreateRequest setOrigin(String origin) {
	// this.origin = origin;
	// return this;
	// }

	@Override
	public FileInfo getFile() {
		return file;
	}

	@Override
	public AssetCreateRequest setFile(FileInfo file) {
		this.file = file;
		return this;
	}

	@Override
	public ConsistencyInfo getConsistency() {
		return consistency;
	}

	@Override
	public AssetCreateRequest setConsistency(ConsistencyInfo consistency) {
		this.consistency = consistency;
		return this;
	}

	public List<EmbeddingCreateRequest> getEmbeddings() {
		return this.embeddings;
	}

	public AssetCreateRequest setEmbeddings(List<EmbeddingCreateRequest> embeddings) {
		this.embeddings = embeddings;
		return this;
	}

	@Override
	public AssetCreateRequest self() {
		return this;
	}

	@JsonIgnore
	public AssetCreateRequest addVideoFingerprint(Float[] vector) {
		getEmbeddings().add(new EmbeddingCreateRequest().setVector(vector).setType(EmbeddingType.VIDEO4J_FINGERPRINT_V2));
		return this;
	}

}
