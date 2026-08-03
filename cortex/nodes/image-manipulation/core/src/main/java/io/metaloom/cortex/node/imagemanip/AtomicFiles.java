package io.metaloom.cortex.node.imagemanip;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishing a finished artifact into the local cache without ever exposing a partial one.
 *
 * <p>
 * The artifact path is cached in-heap and handed downstream to {@code s3-sink}, so a truncated JPEG left behind by a killed worker would be uploaded
 * and registered as a Loom asset. Writing to a {@code .part} sibling and moving it into place closes that window.
 * </p>
 *
 * <p>
 * A near-identical copy lives in the {@code watermark} node, where it is package-private. Promoting one of them into {@code cortex/common} alongside
 * the shared image helpers is tracked as an open item in the node's spec - a third copy is the point at which it stops being worth deferring.
 * </p>
 */
final class AtomicFiles {

	private AtomicFiles() {
	}

	/**
	 * The temporary sibling to write before publishing {@code target}.
	 *
	 * <p>
	 * The marker goes <strong>before</strong> the extension - {@code photo.part.jpg}, not {@code photo.jpg.part}. ImageIO does not care, but the
	 * watermark node's video path does (ffmpeg picks its muxer from the extension), and one rule across the tree is cheaper to remember than two.
	 * </p>
	 */
	static Path partFor(Path target) {
		String name = target.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String part = dot > 0 ? name.substring(0, dot) + ".part" + name.substring(dot) : name + ".part";
		return target.resolveSibling(part);
	}

	/**
	 * Move {@code source} onto {@code target}, replacing any existing file.
	 *
	 * <p>
	 * Falls back to a non-atomic replacing move when the filesystem cannot do it atomically - some network filesystems cannot, and a node that refused
	 * to produce anything there would be worse than one that leaves a narrow window.
	 * </p>
	 */
	static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
