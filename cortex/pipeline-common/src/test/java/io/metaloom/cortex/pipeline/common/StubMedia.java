package io.metaloom.cortex.pipeline.common;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * A media handle that is nothing but a path.
 *
 * <p>
 * {@code StubLoomMedia} in {@code cortex/pipeline-core} would be the thing to reuse, but
 * {@code pipeline-core} depends on <em>this</em> module, so taking its test-jar here would close a
 * dependency cycle. Nothing in this module reads a byte of media - the collector only keys on the
 * handle and prints its path - so a stub with one real field is the whole requirement.
 * </p>
 */
public class StubMedia implements LoomMedia {

	private Path path;
	private SHA512 sha512;

	public StubMedia(String path) {
		this.path = Path.of(path);
	}

	@Override
	public String absolutePath() {
		return path.toString();
	}

	@Override
	public Path path() {
		return path;
	}

	@Override
	public void setPath(Path path) {
		this.path = path;
	}

	@Override
	public File file() {
		return path.toFile();
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
		return false;
	}

	@Override
	public boolean isImage() {
		return false;
	}

	@Override
	public boolean isAudio() {
		return false;
	}

	@Override
	public boolean isDocument() {
		return false;
	}

	@Override
	public long size() {
		return 0;
	}

	@Override
	public boolean exists() {
		return false;
	}

	@Override
	public InputStream open() {
		throw new UnsupportedOperationException("Nothing in pipeline-common reads media content");
	}

	@Override
	public List<String> listXAttr() {
		return List.of();
	}

	@Override
	public String toString() {
		return absolutePath();
	}
}
