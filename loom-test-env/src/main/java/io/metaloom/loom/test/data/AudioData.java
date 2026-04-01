package io.metaloom.loom.test.data;

public interface AudioData extends TestData {

	default TestMedia audio1() {
		return testMedia("folderA/folderC/glossy-168156.mp3")
			.md5("2c32d5bfdba2c9c15c12f3c87b0bc0d0")
			.sha256("a91c00048667d2ece7e66e4d79e83dd0f75606c3a7d46487fb97e2808bdf81a0")
			.sha512(
				"f6dc08a130333e62146b5f154f34195849041ef135d1de58323a6e5c0a13c9ec0773ff7cb75cc8b2cdd436c540f3b76eafb47eb57366035dcb047e8808ba664e")
			.build();
	}

	default TestMedia bogusMP3() {
		return testMedia("folderA/random.mp3").build();
	}

}
