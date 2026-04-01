package io.metaloom.loom.test.data;

public interface OtherData extends TestData {

	default TestMedia bogusBin() {
		return testMedia("folderA/random.bin")
			.md5("3e624a81432e0e060d4c30ae26be4026")
			.sha256("880c8f4daeba96137398e8c1f05da8274bfbecb600ffb15393cc5a594c1da7a7")
			.sha512("1334c7ab351b14f8c99b3c98d3ce1544f954619c24ced26fa53da6a02c56d3edb1ca4c8690f19742d35ba15b1356ee00c02b0190ecbf9d8f2583858d1cf9ada2")
			.build();
	}

}
