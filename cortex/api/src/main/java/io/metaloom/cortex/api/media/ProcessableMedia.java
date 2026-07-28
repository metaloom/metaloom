package io.metaloom.cortex.api.media;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * A media file that can be processed by cortex nodes.
 * This is a pure file handle — no storage or metadata concerns.
 */
public interface ProcessableMedia {

	boolean isVideo();

	boolean isImage();

	boolean isAudio();

	boolean isDocument();

	/**
	 * Return the underlying file for the media.
	 * 
	 * @return
	 */
	File file();

	/**
	 * Return the filesystem path to the media.
	 * 
	 * @return
	 */
	Path path();

	void setPath(Path path);

	/**
	 * Size in bytes.
	 * 
	 * @return
	 * @throws IOException
	 */
	long size() throws IOException;

	/**
	 * Return the absolute filesystem path for the media.
	 *
	 * @return
	 */
	String absolutePath();

	/**
	 * Return the stable, location-independent reference for this media.
	 *
	 * <p>This is what travels between workers in a {@code MediaRef} and what a
	 * downstream worker resolves back into a handle. For filesystem media it is the
	 * absolute path, which is why the default is exactly that and why nothing changes
	 * for the existing implementations. For remote media it is a URI (e.g.
	 * {@code s3://bucket/key}) that any worker can resolve on its own, without the
	 * shared mount that a path would require.</p>
	 *
	 * <p>Note that a reference is deliberately <em>not</em> a {@code Path}:
	 * {@code Paths.get("s3://bucket/key")} collapses the double slash to
	 * {@code s3:/bucket/key}, so a URI cannot survive a round trip through the path
	 * API.</p>
	 *
	 * @return the reference, never null
	 */
	default String reference() {
		return absolutePath();
	}

	/**
	 * Check if the media exists.
	 * 
	 * @return
	 */
	boolean exists();

	/**
	 * Open the media stream.
	 * 
	 * @return
	 * @throws FileNotFoundException
	 */
	InputStream open() throws FileNotFoundException;

	/**
	 * List the extended file attributes for the media file.
	 * 
	 * @return
	 */
	List<String> listXAttr();

}
