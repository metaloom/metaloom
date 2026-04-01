package deprecated.content.field.asset;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import deprecated.content.field.AbstractContentField;
import deprecated.model.model.field.FieldType;
import io.metaloom.loom.rest.model.asset.AssetGeoLocation;
import io.metaloom.loom.rest.model.asset.AssetHash;
import io.metaloom.loom.rest.model.asset.AssetS3Meta;
import io.metaloom.loom.rest.model.asset.workflow.Annotation;
import io.metaloom.loom.rest.model.tag.TagReference;

public class AssetContentField extends AbstractContentField {

	@JsonPropertyDescription("The filename of the asset.")
	private String filename;

	@JsonPropertyDescription("The image width in pixel of the asset.")
	private int width;

	@JsonPropertyDescription("The image height in pixel of the asset.")
	private int height;

	@JsonPropertyDescription("The size of the asset in bytes.")
	private long size;

	@JsonPropertyDescription("Duration of the media in milliseconds.")
	private long duration;

	@JsonPropertyDescription("The mime type of the asset.")
	private String mimeType;

	@JsonPropertyDescription("The computed dominant color of an image.")
	private String dominantColor;

	@JsonPropertyDescription("The location information of the asset. Some images may contain GPS information in the image metadata.")
	private AssetGeoLocation location;

	@JsonPropertyDescription("A list of tags on the asset.")
	private List<TagReference> tags;

	@JsonPropertyDescription("The S3 attributes for the asset. This info is only provides when storage in S3 is utilized or when these values have been provided for the asset.")
	private AssetS3Meta s3;

	@JsonPropertyDescription("Custom metadata that belongs to the asset.")
	private Map<String, String> meta;

	@JsonPropertyDescription("A set of hashes that have been computed for the asset.")
	private AssetHash hashes;

	@JsonPropertyDescription("Stored timeline information.")
	private List<Annotation> timeline;

	@JsonPropertyDescription("The local path of the asset. This will only be returned when the asset was created using a local path.")
	private String localPath;

	@Override
	public AssetContentField setName(String name) {
		super.setName(name);
		return this;
	}

	public String getFilename() {
		return filename;
	}

	public AssetContentField setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	public long getSize() {
		return size;
	}

	public AssetContentField setSize(long size) {
		this.size = size;
		return this;
	}

	public long getDuration() {
		return duration;
	}

	public AssetContentField setDuration(long duration) {
		this.duration = duration;
		return this;
	}

	public int getHeight() {
		return height;
	}

	public AssetContentField setHeight(int height) {
		this.height = height;
		return this;
	}

	public int getWidth() {
		return width;
	}

	public AssetContentField setWidth(int width) {
		this.width = width;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public AssetContentField setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public String getDominantColor() {
		return dominantColor;
	}

	public AssetContentField setDominantColor(String dominantColor) {
		this.dominantColor = dominantColor;
		return this;
	}

	public String getLocalPath() {
		return localPath;
	}

	public AssetContentField setLocalPath(String localPath) {
		this.localPath = localPath;
		return this;
	}

	public AssetGeoLocation getLocation() {
		return location;
	}

	public AssetContentField setLocation(AssetGeoLocation location) {
		this.location = location;
		return this;
	}

	public List<TagReference> getTags() {
		return tags;
	}

	public AssetContentField setTags(List<TagReference> tags) {
		this.tags = tags;
		return this;
	}

	public AssetS3Meta getS3() {
		return s3;
	}

	public AssetContentField setS3(AssetS3Meta s3) {
		this.s3 = s3;
		return this;
	}

	public Map<String, String> getMeta() {
		return meta;
	}

	public AssetContentField setMeta(Map<String, String> meta) {
		this.meta = meta;
		return this;
	}

	public AssetHash getHashes() {
		return hashes;
	}

	public AssetContentField setHashes(AssetHash hashes) {
		this.hashes = hashes;
		return this;
	}

	public List<Annotation> getTimeline() {
		return timeline;
	}

	public AssetContentField setTimeline(List<Annotation> timeline) {
		this.timeline = timeline;
		return this;
	}

	@Override
	public FieldType getType() {
		return FieldType.ASSET;
	}
}
