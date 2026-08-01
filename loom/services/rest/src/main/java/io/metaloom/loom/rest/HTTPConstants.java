package io.metaloom.loom.rest;

public interface HTTPConstants {

	public static final String APPLICATION_JSON = "application/json";

	public static final String TEXT_PLAIN = "text/plain";

	public static final String TEXT_YAML = "text/vnd.yaml";

	/** Content type of the byte-carrying upload routes ({@code /assets/upload}, {@code /binary/data}, {@code /attachments}). */
	public static final String MULTIPART_FORM_DATA = "multipart/form-data";

	/** Fallback content type of the download routes; the actual response carries the binary's stored mime type. */
	public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
}
