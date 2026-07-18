package io.metaloom.loom.pipeline.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reference to a single media item.
 *
 * <p>A reference, never content. The worker that receives it must be able to see
 * the path itself - which is why shared storage is a prerequisite for
 * distributing work across more than one Cortex instance.</p>
 *
 * <p>{@code sha512} is optional and normally absent until a hash node has run.
 * It is carried here because it is how a result finds its asset during sync.</p>
 */
public class MediaRef {

	private final String path;
	private final String sha512;
	private final long size;

	@JsonCreator
	public MediaRef(@JsonProperty("path") String path, @JsonProperty("sha512") String sha512,
		@JsonProperty("size") long size) {
		this.path = Objects.requireNonNull(path, "A media path must be set");
		this.sha512 = sha512;
		this.size = size;
	}

	public static MediaRef of(String path) {
		return new MediaRef(path, null, -1);
	}

	public String getPath() {
		return path;
	}

	/**
	 * @return the SHA-512 of the media, or null when not yet computed
	 */
	public String getSha512() {
		return sha512;
	}

	/**
	 * @return size in bytes, or -1 when unknown
	 */
	public long getSize() {
		return size;
	}

	/**
	 * @param sha512 the hash to attach
	 * @return a copy carrying the given hash
	 */
	public MediaRef withSha512(String sha512) {
		return new MediaRef(path, sha512, size);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MediaRef)) {
			return false;
		}
		MediaRef other = (MediaRef) o;
		return size == other.size && path.equals(other.path) && Objects.equals(sha512, other.sha512);
	}

	@Override
	public int hashCode() {
		return Objects.hash(path, sha512, size);
	}

	@Override
	public String toString() {
		return "MediaRef[" + path + (sha512 != null ? ", sha512=" + sha512.substring(0, Math.min(8, sha512.length())) : "") + "]";
	}
}
