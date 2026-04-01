package io.metaloom.loom.test.container;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import io.metaloom.loom.db.flyway.FlywayHelper;
import io.metaloom.loom.options.LoomOptions;
import io.metaloom.loom.test.dagger.DaggerLoomJooqTestComponent;
import io.metaloom.loom.test.dagger.LoomJooqTestComponent;

public class LoomTestContext extends TestWatcher {

	public static final Logger log = LogManager.getLogger(LoomTestContext.class);

	public LoomPostgreSQLContainer container = new LoomPostgreSQLContainer();

	private LoomJooqTestComponent loomComponent;

	@Override
	protected void starting(Description description) {
		try {
			LoomTestSetting settings = getSettings(description);
			if (description.isSuite()) {
				setupOnce(settings);
			} else {
				setup(settings);
			}

		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}

	}

	private void setupOnce(LoomTestSetting settings) {
		log.info("Starting postgres container");
		container.start();
		log.info("Preparing environment");
		setupEnvironment();
	}

	private void setup(LoomTestSetting settings) {
	}

	@Override
	protected void finished(Description description) {
		try {
			LoomTestSetting settings = getSettings(description);
			if (description.isSuite()) {
				tearDownOnce(settings);
			} else {
				tearDown(settings);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	private void tearDown(LoomTestSetting settings) {
		container.stop();
		container.start();
		setupEnvironment();
	}

	private void tearDownOnce(LoomTestSetting settings) {
		if (container.isRunning()) {
			log.info("Stopping postgres container");
			container.stop();
		}
	}

	public void setupEnvironment() {
		// Prepare settings
		LoomOptions options = new LoomOptions();
		options.setDatabase(container.getOptions());

		// Prepare the database
		log.info("Invoking flyway migration");
		FlywayHelper.migrate(container.getOptions());

		// Now setup loom
		log.info("Creating dagger dependency tree");
		loomComponent = DaggerLoomJooqTestComponent.builder()
			.configuration(options).build();

	}

	/**
	 * Set Features according to the method annotations
	 *
	 * @param description
	 */
	protected LoomTestSetting getSettings(Description description) {
		Class<?> testClass = description.getTestClass();
		if (testClass != null) {
			return testClass.getAnnotation(LoomTestSetting.class);
		}
		return description.getAnnotation(LoomTestSetting.class);
	}

	public LoomJooqTestComponent component() {
		return loomComponent;
	}
}
