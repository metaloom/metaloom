package io.metaloom.loom.test.data;

import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

public interface VideoData extends TestData {

	default TestMedia video1() {
		return testMedia("folderA/folderB/pexels-jack-sparrow-5977265.mp4")
			.sha256(SHA256SUM)
			.sha512(SHA512SUM)
			.md5(MD5SUM)
			.chunkHash(VIDEO_CHUNK_HASH)
			.fingerprint(VIDEO_FINGERPRINT_STR)
			.build();
	}

	default TestMedia video2() {
		return testMedia("folderA/folderB/pexels-mikhail-nilov-7626566.mp4")
			.build();
	}

	default TestMedia video3() {
		return testMedia("folderA/folderC/pexels-fauxels-group-of-friends-smiling-3248275.mp4")
			.build();
	}

	default TestMedia bogusMP4() {
		return testMedia("folderA/random.mp4").build();
	}

	default TestMedia missingMP4() {
		return testMedia("missing.mp4").build();
	}

	default SHA512 sampleVideoSHA512() {
		return SHA512SUM;
	}

	default MD5 sampleVideoMD5() {
		return MD5SUM;
	}

	default String sampleVideoFingerprint() {
		return VIDEO_FINGERPRINT_STR;
	}

	default ChunkHash sampleVideoChunkHash() {
		return VIDEO_CHUNK_HASH;
	}

	default SHA256 sampleVideoSHA256() {
		return SHA256SUM;
	}

}
