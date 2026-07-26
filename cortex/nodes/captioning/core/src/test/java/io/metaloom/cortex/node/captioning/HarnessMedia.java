package io.metaloom.cortex.node.captioning;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.utils.hash.SHA512;

/** Minimal {@link LoomMedia} over a real file on disk, used by the comparison harness to feed videos to the node without a Loom backend. */
public class HarnessMedia implements LoomMedia {

	private final String path;
	private final boolean video;
	private SHA512 sha512;

	public HarnessMedia(String path, boolean video) {
		this.path = path;
		this.video = video;
	}

	public static HarnessMedia video(String path) {
		return new HarnessMedia(path, true);
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
		return f.exists() ? f.length() : 0;
	}

	@Override
	public String absolutePath() {
		return new File(path).getAbsolutePath();
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
}
