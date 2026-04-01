package io.metaloom.loom.test;

import static org.assertj.core.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;

import io.metaloom.loom.test.data.TestDataCollection;
import io.metaloom.test.container.provider.client.TestDatabaseProvider;
import io.metaloom.test.container.provider.common.config.ProviderConfig;
import io.metaloom.utils.fs.XAttrUtils;

public final class TestEnvHelper {

	public static final Path LOCAL_TEST_ENV = Paths.get("/opt/metaloom/loom-testdata");

	private TestEnvHelper() {
	}

	public static TestDataCollection prepareTestdata(String name) throws IOException {
		File source = LOCAL_TEST_ENV.toFile();
		if (!source.exists()) {
			fail("Could not locate local test data dir " + LOCAL_TEST_ENV);
		}
		File dest = new File("target", "test-env-" + name);
		FileUtils.copyDirectory(source, dest);
		Files.walk(dest.toPath()).forEach(path -> XAttrUtils.clearXAttr(path));
		return new TestDataCollection(dest.toPath());

	}

	public static ProviderConfig prepareProvider() {
		ProviderConfig config = new ProviderConfig();
		config.setProviderHost("localhost");
		config.setProviderPort(7543);
		config.getPostgresql().setPassword("sa");
		config.getPostgresql().setUsername("sa");
		config.getPostgresql().setDatabaseName("test");
		config.getPostgresql().setInternalHost("postgresql");
		config.getPostgresql().setInternalPort(5432);
		config.getPostgresql().setHost("localhost");
		config.getPostgresql().setPort(15432);
		TestDatabaseProvider.localConfig(config);
		return config;
	}
}
