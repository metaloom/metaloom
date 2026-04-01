package io.metaloom.loom.rest.model.asset.info;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

public class FileInfo implements RestModel {

	private String mimeType;

	@JsonPropertyDescription("The filename for the asset.")
	private String filename;

	@JsonPropertyDescription("The size of the asset in bytes.")
	private long size;

	private String origin;

	@JsonProperty(required = true)
	@JsonPropertyDescription("ISO8601 formatted date string when the asset was first seen.")
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX")
	private Instant firstSeen;

	public String getMimeType() {
		return mimeType;
	}

	public FileInfo setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public String getFilename() {
		return filename;
	}

	public FileInfo setFilename(String fileName) {
		this.filename = fileName;
		return this;
	}

	public long getSize() {
		return size;
	}

	public FileInfo setSize(long size) {
		this.size = size;
		return this;
	}

	public Instant getFirstSeen() {
		return firstSeen;
	}

	public FileInfo setFirstSeen(Instant firstSeen) {
		this.firstSeen = firstSeen;
		return this;
	}

	public String getOrigin() {
		return origin;
	}

	public FileInfo setOrigin(String origin) {
		this.origin = origin;
		return this;
	}

}
