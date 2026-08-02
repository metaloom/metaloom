package io.metaloom.cortex.cloud;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.utils.fs.FilterHelper;
import io.metaloom.utils.hash.SHA512;

/**
 * A {@link LoomMedia} backed by a cloud-drive file, whose bytes are fetched only when something
 * actually needs them.
 *
 * <p>Used at both ends of a run: the source node builds one per listed file and emits it without
 * downloading anything, and the resolver on whichever worker later receives the node task builds an
 * equivalent one from the reference.</p>
 *
 * <h2>Two deliberate differences from {@code S3LoomMedia}</h2>
 *
 * <p>Both exist because a Google native document is a file with <em>no size</em>, a case object
 * storage never presents:</p>
 * <ul>
 * <li>{@link #size()} returns what the listing said and <b>never falls back to materializing</b>.
 * {@code S3LoomMedia} delegates when the size is unknown, which is safe there because an S3 listing
 * always reports one. Here that fallback would download every Google Doc during enumeration -
 * {@code SourceTaskRunner} asks every emitted item for its size - and it would do so inside a
 * {@code catch} that hides the cost. {@code -1} is a legal answer the runner already tolerates.</li>
 * <li>{@link #exists()} reads an explicit {@link CloudFileRef#present()} flag rather than using
 * {@code size >= 0} as an existence proxy, for the same reason. {@code AbstractMediaNode} asks
 * every item whether it exists before doing any work, so this question must stay free.</li>
 * </ul>
 */
public class CloudLoomMedia implements LoomMedia {

	private final CloudFileRef ref;
	private final CloudMediaMaterializer materializer;

	/** Built on first materialization; guards the download behind a single lock. */
	private volatile LoomMediaImpl delegate;

	public CloudLoomMedia(CloudFileRef ref, CloudMediaMaterializer materializer) {
		if (ref == null) {
			throw new IllegalArgumentException("A file reference must be provided");
		}
		if (materializer == null) {
			throw new IllegalArgumentException("A materializer must be provided");
		}
		this.ref = ref;
		this.materializer = materializer;
	}

	/**
	 * @return the file this handle points at
	 */
	public CloudFileRef ref() {
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

	/**
	 * The size the provider reported, or {@code -1} when it reported none.
	 *
	 * <p>Never materializes - see the class javadoc. A Google native document genuinely has no
	 * size, and enumerating a folder must stay a metadata-only operation.</p>
	 */
	@Override
	public long size() {
		return ref.size();
	}

	/**
	 * The file name as a path, used only for extension-based media-type detection. It is never
	 * resolved against the filesystem.
	 */
	private Path nameAsPath() {
		return Paths.get(ref.uri().fileName());
	}

	@Override
	public boolean isVideo() {
		return FilterHelper.isVideo(nameAsPath());
	}

	@Override
	public boolean isImage() {
		return FilterHelper.isImage(nameAsPath());
	}

	@Override
	public boolean isAudio() {
		return FilterHelper.isAudio(nameAsPath());
	}

	@Override
	public boolean isDocument() {
		return FilterHelper.isDocument(nameAsPath());
	}

	/**
	 * Whether the file exists, answered <em>without</em> fetching it.
	 *
	 * <p>The delegate materializes on construction, so delegating this question would download the
	 * file - the one question that must never cost a transfer, because {@code AbstractMediaNode}
	 * asks it for every item before deciding to do any work.</p>
	 *
	 * <p>{@link CloudFileRef#present()} records whether the provider actually showed us the item.
	 * When it did not, a materialized copy on disk is the only remaining evidence.</p>
	 */
	@Override
	public boolean exists() {
		if (ref.present()) {
			return true;
		}
		LoomMediaImpl local = delegate;
		return local != null && local.exists();
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
