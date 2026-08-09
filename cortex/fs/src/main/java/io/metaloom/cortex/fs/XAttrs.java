package io.metaloom.cortex.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carrying extended attributes across a copy.
 *
 * <p>
 * MetaLoom caches an asset's SHA-512 in the {@code loom_sha512} user attribute, and {@code LoomMediaImpl.getSHA512()} trusts it. Losing it during a
 * relocation is not cosmetic: every downstream node then re-digests the file, turning a metadata operation into a full re-read per item.
 * </p>
 *
 * <p>
 * 🔴 Why this is not just a call to {@code io.metaloom.utils.fs.FileUtils.moveFile}: that helper wraps both its read and its write in
 * {@code catch (Exception e) { e.printStackTrace(); }}. On a filesystem without user-attribute support the attributes vanish with no signal beyond a
 * stack trace on stderr - and "this filesystem has no xattrs" is a benign, expected condition, while "the attribute existed and could not be copied"
 * is a real problem. Collapsing the two into a printed stack trace makes both invisible. {@link XAttrResult} keeps them apart so a caller can log the
 * second and ignore the first.
 * </p>
 */
public final class XAttrs {

	private static final Logger log = LoggerFactory.getLogger(XAttrs.class);

	private XAttrs() {
	}

	/**
	 * The outcome of a snapshot or restore.
	 *
	 * @param attributes
	 *            what was read, empty on anything but a successful read
	 * @param supported
	 *            whether the filesystem offers user-defined attributes at all
	 * @param failure
	 *            the error when the view existed but could not be used, otherwise null
	 */
	public record XAttrResult(Map<String, ByteBuffer> attributes, boolean supported, Exception failure) {

		public boolean isFailure() {
			return failure != null;
		}

		/**
		 * Whether anything was actually captured. An empty snapshot on a supporting filesystem is normal - a file simply may carry no attributes.
		 */
		public boolean isEmpty() {
			return attributes.isEmpty();
		}
	}

	/**
	 * Read every user-defined attribute of {@code path}.
	 *
	 * @param path
	 * @return never null
	 */
	public static XAttrResult snapshot(Path path) {
		UserDefinedFileAttributeView view = Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);
		if (view == null) {
			return new XAttrResult(Map.of(), false, null);
		}
		Map<String, ByteBuffer> data = new LinkedHashMap<>();
		try {
			for (String key : view.list()) {
				int size = view.size(key);
				if (size < 0) {
					throw new IOException("Could not read the size of extended attribute {" + key + "} on " + path);
				}
				ByteBuffer buffer = ByteBuffer.allocate(size);
				// read() advances the buffer's position; duplicate() shares the content while leaving the
				// original positioned at 0, which is what write() needs later.
				view.read(key, buffer.duplicate());
				data.put(key, buffer);
			}
		} catch (Exception e) {
			// An unsupported filesystem answers via a null view above, so reaching here means the view
			// existed and still failed - worth reporting rather than swallowing.
			return new XAttrResult(Map.of(), true, e);
		}
		return new XAttrResult(Map.copyOf(data), true, null);
	}

	/**
	 * Write a previously captured set of attributes onto {@code path}.
	 *
	 * @param path
	 * @param attributes
	 * @return never null
	 */
	public static XAttrResult restore(Path path, Map<String, ByteBuffer> attributes) {
		if (attributes == null || attributes.isEmpty()) {
			return new XAttrResult(Map.of(), true, null);
		}
		UserDefinedFileAttributeView view = Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);
		if (view == null) {
			return new XAttrResult(Map.of(), false, null);
		}
		try {
			for (Map.Entry<String, ByteBuffer> entry : attributes.entrySet()) {
				view.write(entry.getKey(), entry.getValue().duplicate());
			}
		} catch (Exception e) {
			return new XAttrResult(Map.of(), true, e);
		}
		return new XAttrResult(attributes, true, null);
	}

	/**
	 * Log an attribute-carrying failure at the level it deserves, and say which file it was.
	 *
	 * <p>
	 * "The filesystem has no extended attributes" is a deployment fact and stays at debug. Anything else costs a re-hash of the file downstream and is
	 * a warning.
	 * </p>
	 */
	public static void report(XAttrResult result, Path path, String what) {
		if (result.isFailure()) {
			log.warn("Could not {} extended attributes on {} - the cached SHA-512 may have been lost, which forces a re-hash downstream", what, path,
				result.failure());
		} else if (!result.supported()) {
			log.debug("The filesystem holding {} does not support extended attributes; skipping {}", path, what);
		}
	}
}
