package io.metaloom.loom.test.data;

public interface DocData extends TestData {

	default TestMedia docTXT() {
		return testMedia("folderA/sample.txt").build();
	}

	default TestMedia docPDF() {
		return testMedia("folderA/sample.pdf").build();
	}

	default TestMedia docDOCX() {
		return testMedia("folderA/sample.docx")
			.md5("e63db7208fcc07efe837a0e0343bbe06")
			.sha256("d23bb8986bcbbed15c80d71164af1deb2c9b5d22e21767bfc4fb2ece50cefe76")
			.sha512("5580cbaea0066a1fa4a5f5b2aa42c797b10e3936ddf77f6148554c28f99dacfeb5b15148726c106868a0eb4670cabf2790c939d38059e1fd8326f6c0f9f403bd")
			.build();
	}

	default TestMedia docEPUB() {
		return testMedia("folderA/sample.epub").build();
	}

	default TestMedia docODT() {
		return testMedia("folderA/sample.odt").build();
	}

	default TestMedia bogusDoc() {
		return testMedia("folderA/random.doc").build();
	}

}
