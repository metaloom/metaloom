package io.metaloom.cortex.node.imagemanip;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;

/**
 * Reading the EXIF {@code Orientation} tag off an image file.
 *
 * <p>
 * <strong>From the file, not from Loom.</strong> {@code AssetResponse} carries {@code ImageInfo.orientation} and the {@code metadata} node already
 * writes it, but reading it from there would make this node's behaviour depend on whether that node ran and on whether the worker is online. The tag is
 * three bytes into the file; read it there and the operation is offline-safe and order-independent.
 * </p>
 *
 * <p>
 * <strong>metadata-extractor, not Tika</strong> - the same choice {@code MetadataExtractor} in the metadata node makes, for the same reason: Tika's
 * {@code ImageMetadataExtractor} flattens EXIF, IPTC and XMP into one namespace and applies its own precedence on the way.
 * </p>
 */
public final class ExifOrientation {

	private ExifOrientation() {
	}

	/**
	 * The orientation the file declares.
	 *
	 * <p>
	 * <strong>Never throws.</strong> No EXIF block, an unreadable one, a format metadata-extractor does not know, a corrupt tag value - all of them mean
	 * {@link Orientation#NORMAL}. An image whose orientation cannot be established is an image to process as-is, not a failure: PNG has no EXIF at all
	 * and is a perfectly ordinary input to this node.
	 * </p>
	 *
	 * @param file the image file
	 * @return the orientation, never null
	 */
	public static Orientation read(File file) {
		if (file == null || !file.isFile()) {
			return Orientation.NORMAL;
		}
		try (InputStream in = Files.newInputStream(file.toPath())) {
			Metadata metadata = ImageMetadataReader.readMetadata(in);
			for (Directory directory : metadata.getDirectoriesOfType(ExifDirectoryBase.class)) {
				if (directory.containsTag(ExifDirectoryBase.TAG_ORIENTATION)) {
					// getInt throws on a tag that is present but not numeric, which a truncated EXIF block can
					// produce; the outer catch turns that into NORMAL like any other unreadable orientation.
					return Orientation.ofExif(directory.getInt(ExifDirectoryBase.TAG_ORIENTATION));
				}
			}
			return Orientation.NORMAL;
		} catch (Exception e) {
			return Orientation.NORMAL;
		}
	}
}
