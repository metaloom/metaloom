package io.metaloom.loom.test.data;

import java.nio.file.Path;

public class TestDataCollection implements TestValues, VideoData, ImageData, AudioData, OtherData, DocData {

	private Path root;

	public TestDataCollection(Path root) {
		this.root = root;
	}

	@Override
	public Path root() {
		return root;
	}

}
