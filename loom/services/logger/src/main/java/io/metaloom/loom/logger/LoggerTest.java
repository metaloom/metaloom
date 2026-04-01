package io.metaloom.loom.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggerTest {

	public static final Logger logger = LoggerFactory.getLogger(LoggerTest.class);

	public static void main(String[] args) {
		logger.error("Test Error");
		logger.info("Test Info");
		logger.warn("Test Warn");
		logger.debug("Test Debug");
	}

}
