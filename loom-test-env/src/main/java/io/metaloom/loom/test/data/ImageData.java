package io.metaloom.loom.test.data;

public interface ImageData extends TestData {

	default TestMedia image1() {
		return testMedia("folderA/pexels-photo-2379005.jpeg")
			.md5("35589f9bb75999fe07c190fa7c81016d")
			.sha256("e5eebfd33e08248f308cfdca516c6d116b06f7f6d12b991bf63bc970ff1b2585")
			.sha512("0bf777d2955248b4e339f9cd6378f9c39ffdb5f62a305a03f1c3a4a9ee972e31fe9c3939ce43efd089f4d5c66027c3dc1311494d24b3a4328f53e7f2a6589d15")
			.build();
	}

	default TestMedia bogusJPG() {
		return testMedia("folderA/random.jpg").build();
	}

}
