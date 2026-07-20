package io.metaloom.loom.api.options;

/**
 * Options which control where uploaded asset binaries are persisted on disk.
 */
public class StorageOptions implements Option {

	public static final String DEFAULT_UPLOAD_DIRECTORY = "data/storage";

	@EnvironmentVariable(name = "LOOM_STORAGE_UPLOAD_DIR", description = "Override the directory in which uploaded asset binaries are stored.")
	private String uploadDirectory = DEFAULT_UPLOAD_DIRECTORY;

	public String getUploadDirectory() {
		return uploadDirectory;
	}

	public StorageOptions setUploadDirectory(String uploadDirectory) {
		this.uploadDirectory = uploadDirectory;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		errors.notBlank("uploadDirectory", uploadDirectory);
	}

	@Override
	public void overrideWithEnv() {
		OptionUtils.applyEnv("LOOM_STORAGE_UPLOAD_DIR", this::setUploadDirectory);
	}
}
