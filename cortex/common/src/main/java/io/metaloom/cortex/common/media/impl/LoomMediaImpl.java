package io.metaloom.cortex.common.media.impl;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.AbstractFilesystemMedia;
import io.metaloom.utils.fs.FilterHelper;
import io.metaloom.utils.fs.XAttrUtils;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * A pure file handle with content-based identity (SHA-512).
 * SHA-512 is computed on first access and cached in-memory and as an xattr.
 */
@Singleton
public class LoomMediaImpl extends AbstractFilesystemMedia {

	private static final String SHA512_XATTR_KEY = "loom_sha512";

	private Path path;
	private SHA512 sha512Cache;

	@Inject
	public LoomMediaImpl(@Named("mediaPath") Path path) {
		this.path = path;
	}

	@Override
	public Path path() {
		return path;
	}

	public void setPath(Path path) {
		this.path = path;
	}

	@Override
	public long size() throws IOException {
		return Files.size(path);
	}

	@Override
	public boolean isVideo() {
		return FilterHelper.isVideo(path());
	}

	@Override
	public boolean isDocument() {
		return FilterHelper.isDocument(path());
	}

	@Override
	public boolean isImage() {
		return FilterHelper.isImage(path());
	}

	@Override
	public boolean isAudio() {
		return FilterHelper.isAudio(path());
	}

	@Override
	public boolean exists() {
		return file().exists();
	}

	@Override
	public File file() {
		return path.toFile();
	}

	@Override
	public String absolutePath() {
		return file().getAbsolutePath();
	}

	@Override
	public InputStream open() throws FileNotFoundException {
		return new BufferedInputStream(new FileInputStream(file()));
	}

	@Override
	public List<String> listXAttr() {
		return XAttrUtils.listAttr(path());
	}

	@Override
	public SHA512 getSHA512() {
		if (sha512Cache != null) {
			return sha512Cache;
		}

		// Try reading from xattr
		String attrStr = XAttrUtils.readXAttr(path(), SHA512_XATTR_KEY, String.class);
		if (attrStr != null) {
			sha512Cache = SHA512.fromString(attrStr);
			return sha512Cache;
		}

		// Try legacy xattr key
		attrStr = XAttrUtils.readXAttr(path(), "sha512sum", String.class);
		if (attrStr != null) {
			sha512Cache = SHA512.fromString(attrStr);
			setSHA512(sha512Cache);
			return sha512Cache;
		}

		// Compute from file
		sha512Cache = HashUtils.computeSHA512(file());
		setSHA512(sha512Cache);
		return sha512Cache;
	}

	@Override
	public boolean hasSHA512() {
		if (sha512Cache != null) {
			return true;
		}
		return XAttrUtils.hasXAttr(path(), SHA512_XATTR_KEY)
			|| XAttrUtils.hasXAttr(path(), "sha512sum");
	}

	@Override
	public void setSHA512(SHA512 hash) {
		this.sha512Cache = hash;
		XAttrUtils.writeXAttr(path(), SHA512_XATTR_KEY, hash.toString());
	}

	@Override
	public String toString() {
		return shortHash() + ", file: " + absolutePath();
	}

}
