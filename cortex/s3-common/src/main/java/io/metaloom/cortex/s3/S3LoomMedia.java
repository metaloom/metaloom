package io.metaloom.cortex.s3;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.utils.fs.FilterHelper;
import io.metaloom.utils.hash.SHA512;

/**
 * A {@link LoomMedia} backed by an S3 object, whose bytes are fetched only when something
 * actually needs them.
 *
 * <p>Used at both ends of the run: the {@code s3-source} node builds one per listed object and
 * emits it without downloading anything, and the resolver on whichever worker later receives the
 * node task builds an equivalent one from the reference. One class, one materialization path.</p>
 *
 * <p>Three things are answered <em>without</em> touching the network, which is what makes the
 * source enumeration free and lets filter nodes reject objects before any transfer:</p>
 * <ul>
 * <li>{@link #reference()} - the {@code s3://} URI</li>
 * <li>{@link #size()} - from the listing</li>
 * <li>{@link #isVideo()} and friends - from the key's extension</li>
 * </ul>
 *
 * <p>Everything that needs real bytes ({@link #path()}, {@link #file()}, {@link #open()},
 * {@link #getSHA512()}) triggers materialization and then delegates to an ordinary
 * {@link LoomMediaImpl} over the cached file - which is also how the SHA-512 xattr cache keeps
 * working, so a re-materialized object (same etag, same cache path) keeps its hash.</p>
 */
public class S3LoomMedia implements LoomMedia {

	private final S3ObjectRef ref;
	private final S3MediaMaterializer materializer;

	/** Built on first materialization; guards the download behind a single lock. */
	private volatile LoomMediaImpl delegate;

	public S3LoomMedia(S3ObjectRef ref, S3MediaMaterializer materializer) {
		if (ref == null) {
			throw new IllegalArgumentException("An object reference must be provided");
		}
		if (materializer == null) {
			throw new IllegalArgumentException("A materializer must be provided");
		}
		this.ref = ref;
		this.materializer = materializer;
	}

	/**
	 * @return the object this handle points at
	 */
	public S3ObjectRef ref() {
		return ref;
	}

	/**
	 * @return true when the bytes are already on local disk
	 */
	public boolean isMaterialized() {
		return delegate != null;
	}

	private LoomMediaImpl delegate() {
		LoomMediaImpl local = delegate;
		if (local != null) {
			return local;
		}
		synchronized (this) {
			if (delegate == null) {
				delegate = new LoomMediaImpl(materializer.materializeUnchecked(ref));
			}
			return delegate;
		}
	}

	// --- answered without fetching bytes ------------------------------------------------

	@Override
	public String reference() {
		return ref.reference();
	}

	@Override
	public long size() throws IOException {
		return ref.size() >= 0 ? ref.size() : delegate().size();
	}

	/**
	 * The key as a path, used only for extension-based media-type detection. It is never resolved
	 * against the filesystem.
	 */
	private Path keyAsPath() {
		return Paths.get(ref.uri().fileName());
	}

	@Override
	public boolean isVideo() {
		return FilterHelper.isVideo(keyAsPath());
	}

	@Override
	public boolean isImage() {
		return FilterHelper.isImage(keyAsPath());
	}

	@Override
	public boolean isAudio() {
		return FilterHelper.isAudio(keyAsPath());
	}

	@Override
	public boolean isDocument() {
		return FilterHelper.isDocument(keyAsPath());
	}

	// --- require the bytes --------------------------------------------------------------

	@Override
	public Path path() {
		return delegate().path();
	}

	@Override
	public void setPath(Path path) {
		delegate().setPath(path);
	}

	@Override
	public File file() {
		return delegate().file();
	}

	@Override
	public String absolutePath() {
		return delegate().absolutePath();
	}

	/**
	 * Whether the object exists, answered <em>without</em> fetching it.
	 *
	 * <p>Deliberately not delegating: the delegate materializes on construction, so asking whether
	 * media exists would download it - the one question that must never cost a transfer, because
	 * {@code AbstractMediaNode} asks it for every item before deciding to do any work.</p>
	 *
	 * <p>A known size means the listing or HEAD that produced this handle found the object. An
	 * unknown size (-1) means the reference was built for an object the resolver could not find, in
	 * which case a materialized copy on disk is the only remaining evidence.</p>
	 */
	@Override
	public boolean exists() {
		if (ref.size() >= 0) {
			return true;
		}
		LoomMediaImpl local = delegate;
		return local != null && local.exists();
	}

	@Override
	public InputStream open() throws FileNotFoundException {
		return delegate().open();
	}

	@Override
	public List<String> listXAttr() {
		return delegate().listXAttr();
	}

	@Override
	public SHA512 getSHA512() {
		return delegate().getSHA512();
	}

	@Override
	public void setSHA512(SHA512 hash) {
		delegate().setSHA512(hash);
	}

	@Override
	public boolean hasSHA512() {
		return delegate().hasSHA512();
	}

	@Override
	public String toString() {
		return reference() + (isMaterialized() ? " -> " + delegate().absolutePath() : " (not materialized)");
	}
}
