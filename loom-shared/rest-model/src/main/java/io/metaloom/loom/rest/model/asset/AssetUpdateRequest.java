package io.metaloom.loom.rest.model.asset;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

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
import io.metaloom.loom.rest.model.asset.location.AssetS3Meta;
import io.metaloom.loom.rest.model.tag.TagReference;
import io.metaloom.loom.rest.validation.ReplaceOptional;
import io.vertx.core.json.JsonObject;

public class AssetUpdateRequest implements RestRequestModel, AssetModel<AssetUpdateRequest> {

	// @JsonPropertyDescription("The specific identified kind of asset.")
	// private AssetKind kind;

	// No top level "filename" or "dominantColor" here. Both used to be declared and neither was ever read:
	// the update path takes the filename from file.filename and the dominant colour from
	// image.dominantColor, which is also the only shape AssetCreateRequest accepts and the only one
	// AssetResponse returns. Declaring them meant a client could set one, get a 200, and find nothing had
	// changed - so they are gone rather than quietly ignored.

	@JsonPropertyDescription("Custom meta properties for the asset.")
	private JsonObject meta;

	@JsonPropertyDescription("Timeline information on the asset.")
	@ReplaceOptional
	private List<AnnotationResponse> timeline;

	@JsonPropertyDescription("S3 meta information on the asset. (only set when S3 is being utilized).")
	@ReplaceOptional
	private AssetS3Meta s3;

	// @JsonPropertyDescription("The local path of the asset. This will only be returned when the asset was created using a local path.")
	// private String localPath;

	@JsonPropertyDescription("A list of tags on the asset.")
	private List<TagReference> tags;

	@JsonPropertyDescription("Information about the asset file.")
	private FileInfo file;

	@JsonPropertyDescription("Information about consistency checks on the the asset.")
	@ReplaceOptional
	private ConsistencyInfo consistency;

	@JsonPropertyDescription("A set of different computed hashes for the asset.")
	private HashInfo hashes;

	@JsonPropertyDescription("Information about the media fingerprints")
	@ReplaceOptional
	private FingerprintInfo fingerprint;

	@JsonPropertyDescription("Information about common media properties (e.g. duration, dimension)")
	private MediaInfo media;

	@JsonPropertyDescription("Information about the image component of the asset (if present)")
	@ReplaceOptional
	private ImageInfo image;

	@JsonPropertyDescription("Information about the video component of the asset (if present)")
	@ReplaceOptional
	private VideoInfo video;

	@JsonPropertyDescription("Information about the audio component of the asset (if present)")
	@ReplaceOptional
	private AudioInfo audio;

	@JsonPropertyDescription("Information about the document (text) component of the asset (if present)")
	@ReplaceOptional
	private DocumentInfo document;

	@JsonPropertyDescription("The geo spatial location of the asset.")
	@ReplaceOptional
	private GeoLocationInfo geo;

	public AssetUpdateRequest() {
	}

	// public AssetKind getKind() {
	// return kind;
	// }
	//
	// public AssetUpdateRequest setKind(AssetKind kind) {
	// this.kind = kind;
	// return this;
	// }

	public AssetS3Meta getS3() {
		return s3;
	}

	public AssetUpdateRequest setS3(AssetS3Meta s3) {
		this.s3 = s3;
		return this;
	}

	@Override
	public FileInfo getFile() {
		return file;
	}

	@Override
	public AssetUpdateRequest setFile(FileInfo file) {
		this.file = file;
		return this;
	}

	@Override
	public ConsistencyInfo getConsistency() {
		return consistency;
	}

	@Override
	public AssetUpdateRequest setConsistency(ConsistencyInfo consistency) {
		this.consistency = consistency;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public AssetUpdateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	public List<AnnotationResponse> getTimeline() {
		return timeline;
	}

	public AssetUpdateRequest setTimeline(List<AnnotationResponse> timeline) {
		this.timeline = timeline;
		return this;
	}

	// public String getLocalPath() {
	// return localPath;
	// }
	//
	// public AssetUpdateRequest setLocalPath(String localPath) {
	// this.localPath = localPath;
	// return this;
	// }

	public List<TagReference> getTags() {
		return tags;
	}

	public AssetUpdateRequest setTags(List<TagReference> tags) {
		this.tags = tags;
		return this;
	}

	@Override
	public HashInfo getHashes() {
		return hashes;
	}

	@Override
	public AssetUpdateRequest setHashes(HashInfo hashes) {
		this.hashes = hashes;
		return this;
	}

	@Override
	public FingerprintInfo getFingerprint() {
		return fingerprint;
	}

	@Override
	public AssetUpdateRequest setFingerprint(FingerprintInfo fingerprint) {
		this.fingerprint = fingerprint;
		return this;
	}

	public MediaInfo getMedia() {
		return media;
	}

	public AssetUpdateRequest setMedia(MediaInfo media) {
		this.media = media;
		return this;
	}

	public AudioInfo getAudio() {
		return audio;
	}

	public AssetUpdateRequest setAudio(AudioInfo audio) {
		this.audio = audio;
		return this;
	}

	public ImageInfo getImage() {
		return image;
	}

	public AssetUpdateRequest setImage(ImageInfo image) {
		this.image = image;
		return this;
	}

	public VideoInfo getVideo() {
		return video;
	}

	public AssetUpdateRequest setVideo(VideoInfo video) {
		this.video = video;
		return this;
	}

	public DocumentInfo getDocument() {
		return document;
	}

	public AssetUpdateRequest setDocument(DocumentInfo document) {
		this.document = document;
		return this;
	}

	@Override
	public GeoLocationInfo getGeo() {
		return geo;
	}

	@Override
	public AssetUpdateRequest setGeo(GeoLocationInfo geo) {
		this.geo = geo;
		return this;
	}

	@Override
	public AssetUpdateRequest self() {
		return this;
	}

}
