package io.metaloom.cortex.pipeline.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * Minimal {@link LoomMedia} stub for pipeline tests.
 *
 * <p>When constructed with a path that points to an existing file, {@link #open()}
 * returns a real {@link FileInputStream}; otherwise it throws. This allows
 * nodes that need to read file content (e.g. hash nodes) to work correctly
 * while keeping the stub lightweight.</p>
 */
public class StubLoomMedia implements LoomMedia {

	private final String path;
	private final boolean video;
	private final boolean image;
	private final boolean audio;
	private final boolean document;
	private SHA512 sha512;

	public StubLoomMedia(String path) {
		this(path, false, false, false, false);
	}

	public StubLoomMedia(String path, boolean video, boolean image, boolean audio, boolean document) {
		this.path = path;
		this.video = video;
		this.image = image;
		this.audio = audio;
		this.document = document;
	}

	public static StubLoomMedia ofFile(File file) {
		return new StubLoomMedia(file.getAbsolutePath());
	}

	/**
	 * Write {@code content} to a new file {@code name} under {@code tempDir}
	 * and return a media stub pointing at it. Convenience factory that removes
	 * the {@code Files.write(...)} preamble from every node-test {@code @BeforeEach}.
	 */
	public static StubLoomMedia ofBytes(File tempDir, String name, byte[] content) {
		File file = new File(tempDir, name);
		try {
			Files.write(file.toPath(), content);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return ofFile(file);
	}

	/**
	 * {@link #ofBytes(File, String, byte[])} using the UTF-8 bytes of {@code content}.
	 */
	public static StubLoomMedia ofBytes(File tempDir, String name, String content) {
		return ofBytes(tempDir, name, content.getBytes());
	}

	@Override
	public SHA512 getSHA512() {
		return sha512;
	}

	@Override
	public void setSHA512(SHA512 hash) {
		this.sha512 = hash;
	}

	@Override
	public boolean hasSHA512() {
		return sha512 != null;
	}

	@Override
	public boolean isVideo() {
		return video;
	}

	@Override
	public boolean isImage() {
		return image;
	}

	@Override
	public boolean isAudio() {
		return audio;
	}

	@Override
	public boolean isDocument() {
		return document;
	}

	@Override
	public File file() {
		return new File(path);
	}

	@Override
	public Path path() {
		return Path.of(path);
	}

	@Override
	public void setPath(Path path) {
	}

	@Override
	public long size() {
		File f = file();
		return f.exists() ? f.length() : 1024;
	}

	@Override
	public String absolutePath() {
		return path;
	}

	@Override
	public boolean exists() {
		return file().exists();
	}

	@Override
	public InputStream open() throws FileNotFoundException {
		return new FileInputStream(file());
	}

	@Override
	public List<String> listXAttr() {
		return List.of();
	}

	@Override
	public String toString() {
		return "StubLoomMedia{" + path + "}";
	}
}
