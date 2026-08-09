package io.metaloom.cortex.fs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.fs.XAttrs.XAttrResult;

/**
 * ⚠️ Extended attributes are a filesystem capability, not a Java one. Every test here assumes support and skips without it, which is the honest
 * behaviour on tmpfs and on several network filesystems.
 */
public class XAttrsTest {

	private static final String SHA512_KEY = "loom_sha512";

	@TempDir
	Path dir;

	private Path file;

	@BeforeEach
	public void setup() throws IOException {
		file = Files.writeString(dir.resolve("clip.mp4"), "payload");
		assumeTrue(supportsXAttr(file), "The filesystem under the temp dir does not support user-defined attributes");
	}

	private boolean supportsXAttr(Path path) {
		UserDefinedFileAttributeView view = Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);
		if (view == null) {
			return false;
		}
		try {
			view.write("probe", ByteBuffer.wrap("x".getBytes(UTF_8)));
			view.delete("probe");
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Test
	public void testSnapshotAndRestoreCarryTheCachedHash() throws IOException {
		writeAttr(file, SHA512_KEY, "deadbeef");

		XAttrResult snapshot = XAttrs.snapshot(file);
		assertTrue(snapshot.supported());
		assertFalse(snapshot.isFailure());
		assertTrue(snapshot.attributes().containsKey(SHA512_KEY));

		Path copy = Files.writeString(dir.resolve("copy.mp4"), "payload");
		XAttrResult restored = XAttrs.restore(copy, snapshot.attributes());
		assertFalse(restored.isFailure());

		assertEquals("deadbeef", readAttr(copy, SHA512_KEY),
			"The cached SHA-512 must survive a copy, or every downstream node re-digests the file");
	}

	@Test
	public void testSnapshotCarriesEveryAttributeNotJustTheKnownOnes() throws IOException {
		writeAttr(file, SHA512_KEY, "deadbeef");
		writeAttr(file, "some_other_marker", "kept");

		Path copy = Files.writeString(dir.resolve("copy.mp4"), "payload");
		XAttrs.restore(copy, XAttrs.snapshot(file).attributes());

		assertEquals("kept", readAttr(copy, "some_other_marker"), "Attributes this code does not know about must travel too");
	}

	@Test
	public void testAFileWithNoAttributesSnapshotsEmptyAndIsNotAFailure() {
		XAttrResult snapshot = XAttrs.snapshot(file);
		assertTrue(snapshot.isEmpty());
		assertFalse(snapshot.isFailure(), "No attributes is a normal state, not an error");
	}

	@Test
	public void testRestoringNothingIsANoOp() {
		XAttrResult result = XAttrs.restore(file, java.util.Map.of());
		assertFalse(result.isFailure());
	}

	private void writeAttr(Path path, String key, String value) throws IOException {
		Files.getFileAttributeView(path, UserDefinedFileAttributeView.class).write(key, ByteBuffer.wrap(value.getBytes(UTF_8)));
	}

	private String readAttr(Path path, String key) throws IOException {
		UserDefinedFileAttributeView view = Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);
		ByteBuffer buffer = ByteBuffer.allocate(view.size(key));
		view.read(key, buffer);
		buffer.flip();
		return UTF_8.decode(buffer).toString();
	}
}
