package io.metaloom.loom.rest.model.asset.location;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class AssetLocationFilesystemInfo {

	@JsonPropertyDescription("Current path to the file")
	private String path;

	@JsonPropertyDescription("Linux filesystem key which identifies the file in the filesystem")
	private FileKey filekey;

	@JsonPropertyDescription("ISO8601 formatted date string when the asset was last seen online.")
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX")
	private Instant lastSeen;

	public String getPath() {
		return path;
	}

	public AssetLocationFilesystemInfo setPath(String path) {
		this.path = path;
		return this;
	}

	public FileKey getFilekey() {
		return filekey;
	}

	public AssetLocationFilesystemInfo setFilekey(FileKey filekey) {
		this.filekey = filekey;
		return this;
	}

	public Instant getLastSeen() {
		return lastSeen;
	}

	public AssetLocationFilesystemInfo setLastSeen(Instant lastSeen) {
		this.lastSeen = lastSeen;
		return this;
	}
}
