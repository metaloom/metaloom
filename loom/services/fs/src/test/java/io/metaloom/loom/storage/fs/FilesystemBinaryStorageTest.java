package io.metaloom.loom.storage.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.storage.BinaryStorageException;
import io.metaloom.loom.storage.StorageKeys;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

public class FilesystemBinaryStorageTest {

	@TempDir
	Path tmp;

	private Path sourceFile(String content) throws IOException {
		Path file = Files.createTempFile(tmp, "src", ".bin");
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private SHA512 hashOf(Path file) {
		return HashUtils.computeSHA512(file);
	}

	@Test
	public void shouldStoreUnderTheSegmentedContentAddress() throws IOException {
		Path base = tmp.resolve("storage");
		FilesystemBinaryStorage storage = new FilesystemBinaryStorage(base.toString());
		Path source = sourceFile("hello-bytes");
		SHA512 hash = hashOf(source);

		String locator = storage.store(source, hash, "text/plain");

		assertThat(Path.of(locator)).isEqualTo(base.resolve(StorageKeys.contentKey(hash)));
		assertThat(Files.readString(Path.of(locator))).isEqualTo("hello-bytes");
		// The locator is the full path, not the relative key: the Cortex worker opens
		// asset_location.path directly off its own filesystem.
		assertThat(locator).startsWith(base.toString());
	}

	@Test
	public void shouldDeriveTheSameLocatorItWouldStoreAt() throws IOException {
		FilesystemBinaryStorage storage = new FilesystemBinaryStorage(tmp.resolve("storage").toString());
		Path source = sourceFile("derive-me");
		SHA512 hash = hashOf(source);

		// locatorFor is what the attachment download path relies on, since attachment_binary
		// records only the hash and the pool.
		assertThat(storage.locatorFor(hash)).isEqualTo(storage.store(source, hash, null));
	}

	@Test
	public void shouldDeduplicateIdenticalContent() throws IOException {
		FilesystemBinaryStorage storage = new FilesystemBinaryStorage(tmp.resolve("storage").toString());
		Path first = sourceFile("same-content");
		Path second = sourceFile("same-content");

		String a = storage.store(first, hashOf(first), null);
		String b = storage.store(second, hashOf(second), null);

		assertThat(a).isEqualTo(b);
	}

	@Test
	public void shouldReadAByteRange() throws IOException {
		FilesystemBinaryStorage storage = new FilesystemBinaryStorage(tmp.resolve("storage").toString());
		Path source = sourceFile("0123456789");
		String locator = storage.store(source, hashOf(source), null);

		try (InputStream in = storage.read(locator, 3, 4)) {
			assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("3456");
		}
		// A negative length means "to the end", which is what an open-ended Range maps to.
		try (InputStream in = storage.read(locator, 8, -1)) {
			assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("89");
		}
	}

	@Test
	public void shouldReportSizeAndExistence() throws IOException {
		FilesystemBinaryStorage storage = new FilesystemBinaryStorage(tmp.resolve("storage").toString());
		Path source = sourceFile("12345");
		String locator = storage.store(source, hashOf(source), null);

		assertThat(storage.exists(locator)).isTrue();
		assertThat(storage.size(locator)).isEqualTo(5);
		assertThat(storage.localPath(locator)).contains(Path.of(locator));
	}

	@Test
	public void shouldTreatDeletingAbsentBytesAsSuccess() throws IOException {
		FilesystemBinaryStorage storage = new FilesystemBinaryStorage(tmp.resolve("storage").toString());
		Path source = sourceFile("gone-soon");
		String locator = storage.store(source, hashOf(source), null);

		storage.delete(locator);
		assertThat(storage.exists(locator)).isFalse();
		// The reclaimer has already established nothing references these bytes; a second delete
		// finding nothing is the state it wanted, not an error.
		storage.delete(locator);
	}

	@Test
	public void shouldFailReadingSomethingItNeverStored() {
		FilesystemBinaryStorage storage = new FilesystemBinaryStorage(tmp.resolve("storage").toString());
		assertThatThrownBy(() -> storage.read(tmp.resolve("nope").toString(), 0, -1))
			.isInstanceOf(BinaryStorageException.class);
	}

	@Test
	public void shouldReportFreeSpaceBeforeTheDirectoryExists() {
		// A fresh install has not created the upload directory yet, and the capacity check runs
		// before the first store. Walking up to an existing parent is what makes that answerable.
		FilesystemBinaryStorage storage = new FilesystemBinaryStorage(tmp.resolve("not/created/yet").toString());
		assertThat(storage.freeSpace()).isNotNull().isPositive();
	}

	@Test
	public void shouldRejectABlankBaseDirectory() {
		assertThatThrownBy(() -> new FilesystemBinaryStorage("  ")).isInstanceOf(IllegalArgumentException.class);
	}
}
