package io.metaloom.loom.test.data;

import java.nio.file.Path;

import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

public interface TestMedia {

	MD5 md5();

	SHA512 sha512();

	SHA256 sha256();

	Path path();

	ChunkHash chunkHash();

	String fingerprint();

	String sceneDetection();

}
