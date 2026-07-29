package io.metaloom.loom.pipeline.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reference to a single media item.
 *
 * <p>A reference, never content. What the string means depends on the source:</p>
 *
 * <ul>
 * <li>An <b>absolute path</b>, for filesystem media. The worker that receives it must be able to
 * see that path itself, so shared storage is a prerequisite for distributing work across more than
 * one Cortex instance.</li>
 * <li>A <b>URI</b> such as {@code s3://bucket/key}, for remote media. Any worker can resolve it on
 * its own by fetching the object into its local cache, so no shared storage is needed.</li>
 * </ul>
 *
 * <p>Resolution happens through {@code MediaReferenceResolver} - which is also why this is a
 * plain string rather than a {@link java.nio.file.Path}: a path cannot hold a URI, because
 * {@code Paths.get("s3://bucket/key")} collapses the double slash to {@code s3:/bucket/key}.</p>
 *
 * <p>{@code sha512} is optional and normally absent until a hash node has run.
 * It is carried here because it is how a result finds its asset during sync.</p>
 */
public class MediaRef {

	/**
	 * What kind of media this is, as far as the source could tell.
	 *
	 * <p>
	 * Best-effort by design: a source infers it from a file extension or an object listing, so
	 * {@link #UNKNOWN} is a normal answer, not an error. It exists so a {@code media/image} input can
	 * be checked at all — nothing on the wire used to say what the item was, which made the
	 * image-versus-video distinction in a node's declared inputs unverifiable. Save-time validation
	 * accepts an unspecific source through the wildcard rule; the real check happens on the worker,
	 * where the file itself can be inspected.
	 * </p>
	 */
	public static final String IMAGE = "image";
	public static final String VIDEO = "video";
	public static final String AUDIO = "audio";
	public static final String DOCUMENT = "document";
	public static final String UNKNOWN = "unknown";

	private final String path;
	private final String sha512;
	private final long size;
	private final String mediaType;

	@JsonCreator
	public MediaRef(@JsonProperty("path") String path, @JsonProperty("sha512") String sha512,
		@JsonProperty("size") long size, @JsonProperty("mediaType") String mediaType) {
		this.path = Objects.requireNonNull(path, "A media path must be set");
		this.sha512 = sha512;
		this.size = size;
		this.mediaType = mediaType == null ? UNKNOWN : mediaType;
	}

	public MediaRef(String path, String sha512, long size) {
		this(path, sha512, size, UNKNOWN);
	}

	public static MediaRef of(String path) {
		return new MediaRef(path, null, -1, UNKNOWN);
	}

	public static MediaRef of(String path, String mediaType) {
		return new MediaRef(path, null, -1, mediaType);
	}

	public String getPath() {
		return path;
	}

	/**
	 * @return {@code image}, {@code video}, {@code audio}, {@code document} or {@code unknown};
	 *         never null
	 */
	public String getMediaType() {
		return mediaType;
	}

	/**
	 * The content type this item satisfies, e.g. {@code media/image}.
	 *
	 * @return {@code media/*} when the kind is unknown, which the lattice treats as compatible with
	 *         any media input
	 */
	public String contentType() {
		return UNKNOWN.equals(mediaType) ? "media/*" : "media/" + mediaType;
	}

	/**
	 * @param mediaType the kind to attach
	 * @return a copy carrying the given media type
	 */
	public MediaRef withMediaType(String mediaType) {
		return new MediaRef(path, sha512, size, mediaType);
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
		return new MediaRef(path, sha512, size, mediaType);
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
		return size == other.size && path.equals(other.path) && Objects.equals(sha512, other.sha512)
			&& Objects.equals(mediaType, other.mediaType);
	}

	@Override
	public int hashCode() {
		return Objects.hash(path, sha512, size, mediaType);
	}

	@Override
	public String toString() {
		return "MediaRef[" + path + (sha512 != null ? ", sha512=" + sha512.substring(0, Math.min(8, sha512.length())) : "") + "]";
	}
}
