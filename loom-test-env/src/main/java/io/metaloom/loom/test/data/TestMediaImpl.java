package io.metaloom.loom.test.data;

import java.nio.file.Path;

import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

public class TestMediaImpl implements TestMedia {

	private final Path path;

	private MD5 md5;

	private SHA256 sha256;

	private SHA512 sha512;

	private ChunkHash chunkHash;

	private String fingerprint;

	private String sceneDetection;

	public TestMediaImpl(TestMediaBuilder builder) {
		this.md5 = builder.md5;
		this.path = builder.path;
		this.sha256 = builder.sha256;
		this.sha512 = builder.sha512;
		this.chunkHash = builder.chunkHash;
		this.fingerprint = builder.fingerprint;
		this.sceneDetection = builder.sceneDetection;
	}

	@Override
	public MD5 md5() {
		return md5;
	}

	@Override
	public SHA256 sha256() {
		return sha256;
	}

	@Override
	public SHA512 sha512() {
		return sha512;
	}

	@Override
	public ChunkHash chunkHash() {
		return chunkHash;
	}

	@Override
	public String fingerprint() {
		return fingerprint;
	}

	@Override
	public String sceneDetection() {
		return sceneDetection;
	}

	@Override
	public Path path() {
		return path;
	}

	public static class TestMediaBuilder {

		private final Path path;

		private MD5 md5;

		private SHA256 sha256;

		private SHA512 sha512;

		private ChunkHash chunkHash;

		private String fingerprint;

		private String sceneDetection;

		public TestMediaBuilder(Path path) {
			this.path = path;
		}

		public static TestMediaBuilder newBuilder(Path path) {
			return new TestMediaBuilder(path);
		}

		public TestMediaBuilder sha512(SHA512 sha512) {
			this.sha512 = sha512;
			return this;
		}

		public TestMediaBuilder sha512(String sha512) {
			return sha512(SHA512.fromString(sha512));
		}

		public TestMediaBuilder md5(String md5) {
			return md5(MD5.fromString(md5));
		}

		public TestMediaBuilder md5(MD5 md5) {
			this.md5 = md5;
			return this;
		}

		public TestMediaBuilder sha256(String sha256) {
			return sha256(SHA256.fromString(sha256));
		}

		public TestMediaBuilder sha256(SHA256 sha256) {
			this.sha256 = sha256;
			return this;
		}

		public TestMediaBuilder chunkHash(String chunkHash) {
			return chunkHash(ChunkHash.fromString(chunkHash));
		}

		public TestMediaBuilder chunkHash(ChunkHash chunkHash) {
			this.chunkHash = chunkHash;
			return this;
		}

		public TestMediaBuilder fingerprint(String fp) {
			this.fingerprint = fp;
			return this;
		}

		public TestMediaBuilder sceneDetection(String detection) {
			this.sceneDetection = detection;
			return this;
		}

		public TestMedia build() {
			return new TestMediaImpl(this);
		}

	}

}
