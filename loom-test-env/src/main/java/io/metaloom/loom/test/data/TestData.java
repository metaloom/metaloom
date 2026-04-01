package io.metaloom.loom.test.data;

import java.nio.file.Path;

import io.metaloom.loom.test.data.TestMediaImpl.TestMediaBuilder;

public interface TestData extends TestValues {

	Path root();

	default TestMediaBuilder testMedia(String path) {
		return TestMediaBuilder.newBuilder(root().resolve(path));
	}

}
