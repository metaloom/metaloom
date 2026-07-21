package io.metaloom.loom.api.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.error.ConfigurationValidationException;

public class LoomOptionsValidationTest {

	/**
	 * Build an option tree which is expected to pass validation. Note that a plain {@link LoomOptions} is <b>not</b> valid since the keystore
	 * password is only assigned by {@code LoomOptionsLoader#generateDefaultConfig()}.
	 */
	private LoomOptions validOptions() {
		LoomOptions options = new LoomOptions();
		options.getAuth().setKeystorePassword("8qA9uBbdaEFp");
		return options;
	}

	private List<String> errorsOf(LoomOptions options) {
		ConfigurationValidationException e = assertThrows(ConfigurationValidationException.class, options::validate);
		return e.getErrors();
	}

	/**
	 * Assert that exactly one error was raised and that it refers to the given path.
	 */
	private void assertSingleError(LoomOptions options, String expectedPath) {
		List<String> errors = errorsOf(options);
		assertEquals(1, errors.size(), "Expected a single error but got: " + errors);
		assertTrue(errors.get(0).startsWith(expectedPath + ":"), "Expected error for {" + expectedPath + "} but got: " + errors.get(0));
	}

	@Test
	public void testDefaultConfigIsValid() {
		validOptions().validate();
	}

	// -- Missing required settings -------------------------------------------

	@Test
	public void testMissingKeystorePassword() {
		LoomOptions options = validOptions();
		options.getAuth().setKeystorePassword(null);
		assertSingleError(options, "auth.keystorePassword");
	}

	@Test
	public void testBlankDatabasePassword() {
		LoomOptions options = validOptions();
		options.getDatabase().setPassword("");
		assertSingleError(options, "database.password");
	}

	@Test
	public void testBlankDatabaseUsername() {
		LoomOptions options = validOptions();
		options.getDatabase().setUsername("   ");
		assertSingleError(options, "database.username");
	}

	@Test
	public void testNullSubOptionIsReported() {
		LoomOptions options = validOptions();
		options.setDatabase(null);
		assertSingleError(options, "database");
	}

	// -- Invalid values ------------------------------------------------------

	@Test
	public void testInvalidDatabasePort() {
		LoomOptions options = validOptions();
		options.getDatabase().setPort(0);
		assertSingleError(options, "database.port");
	}

	@Test
	public void testInvalidRestPort() {
		LoomOptions options = validOptions();
		options.getServer().setRestPort(70000);
		assertSingleError(options, "server.restPort");
	}

	@Test
	public void testInvalidMcpPort() {
		LoomOptions options = validOptions();
		options.getServer().setMcpPort(70000);
		assertSingleError(options, "server.mcpPort");
	}

	@Test
	public void testDuplicateMcpPort() {
		LoomOptions options = validOptions();
		options.getServer().setMcpPort(options.getServer().getRestPort());
		List<String> errors = errorsOf(options);
		assertEquals(1, errors.size(), "Expected a single error but got: " + errors);
		assertTrue(errors.get(0).contains("must not use the same port as restPort"), "Unexpected message: " + errors.get(0));
	}

	@Test
	public void testNegativePoolSize() {
		LoomOptions options = validOptions();
		options.getDatabase().setMinPoolSize(-1);
		assertSingleError(options, "database.minPoolSize");
	}

	@Test
	public void testMaxPoolSizeBelowMinPoolSize() {
		LoomOptions options = validOptions();
		options.getDatabase().setMinPoolSize(20).setMaxPoolSize(5);
		assertSingleError(options, "database.maxPoolSize");
	}

	@Test
	public void testZeroTokenExpirationTime() {
		LoomOptions options = validOptions();
		options.getAuth().setTokenExpirationTime(0);
		assertSingleError(options, "auth.tokenExpirationTime");
	}

	@Test
	public void testInvalidBindAddress() {
		LoomOptions options = validOptions();
		options.getServer().setBindAddress("not a host");
		assertSingleError(options, "server.bindAddress");
	}

	@Test
	public void testDuplicateServerPorts() {
		LoomOptions options = validOptions();
		options.getServer().setRestPort(options.getServer().getGrpcPort());
		List<String> errors = errorsOf(options);
		assertEquals(1, errors.size(), "Expected a single error but got: " + errors);
		assertTrue(errors.get(0).contains("must not use the same port as grpcPort"), "Unexpected message: " + errors.get(0));
	}

	// -- OAuth2 --------------------------------------------------------------

	@Test
	public void testDisabledOAuth2IsNotValidated() {
		LoomOptions options = validOptions();
		// Left entirely unconfigured - must not raise anything while disabled.
		options.getAuth().getOauth2().setEnabled(false);
		options.validate();
	}

	@Test
	public void testEnabledOAuth2RequiresFullConfiguration() {
		LoomOptions options = validOptions();
		options.getAuth().getOauth2().setEnabled(true);
		List<String> errors = errorsOf(options);

		// clientId, clientSecret and the four required URLs.
		assertEquals(6, errors.size(), "Expected all missing OAuth2 settings to be reported but got: " + errors);
		assertTrue(errors.stream().allMatch(e -> e.startsWith("auth.oauth2.")), "Unexpected errors: " + errors);
	}

	@Test
	public void testEnabledOAuth2RejectsNonAbsoluteUrl() {
		LoomOptions options = validOptions();
		OAuth2Options oauth2 = fullyConfiguredOAuth2();
		oauth2.setAuthUrl("/oauth2/authorize");
		options.getAuth().setOauth2(oauth2);
		assertSingleError(options, "auth.oauth2.authUrl");
	}

	@Test
	public void testEnabledOAuth2RejectsNonHttpScheme() {
		LoomOptions options = validOptions();
		OAuth2Options oauth2 = fullyConfiguredOAuth2();
		oauth2.setTokenUrl("ftp://idp.example.com/token");
		options.getAuth().setOauth2(oauth2);
		assertSingleError(options, "auth.oauth2.tokenUrl");
	}

	@Test
	public void testEnabledOAuth2AcceptsMissingLogoutUrl() {
		LoomOptions options = validOptions();
		OAuth2Options oauth2 = fullyConfiguredOAuth2();
		oauth2.setLogoutUrl(null);
		options.getAuth().setOauth2(oauth2);
		options.validate();
	}

	@Test
	public void testEnabledOAuth2RejectsInvalidLogoutUrl() {
		LoomOptions options = validOptions();
		OAuth2Options oauth2 = fullyConfiguredOAuth2();
		oauth2.setLogoutUrl("nonsense");
		options.getAuth().setOauth2(oauth2);
		assertSingleError(options, "auth.oauth2.logoutUrl");
	}

	private OAuth2Options fullyConfiguredOAuth2() {
		return new OAuth2Options()
			.setEnabled(true)
			.setClientId("loom")
			.setClientSecret("s3cret")
			.setAuthUrl("https://idp.example.com/authorize")
			.setTokenUrl("https://idp.example.com/token")
			.setUserInfoUrl("https://idp.example.com/userinfo")
			.setCallbackUrl("https://loom.example.com/api/v1/auth/oauth2/callback")
			.setLogoutUrl("https://idp.example.com/logout");
	}

	// -- Error reporting -----------------------------------------------------

	@Test
	public void testAllErrorsAreReportedTogether() {
		LoomOptions options = new LoomOptions();
		options.getDatabase().setPort(0).setHost("").setMaxPoolSize(0);
		options.getServer().setRestPort(-1);
		// keystorePassword is left null

		List<String> errors = errorsOf(options);
		assertEquals(5, errors.size(), "Expected every problem to be collected but got: " + errors);
		assertTrue(errors.stream().anyMatch(e -> e.startsWith("database.host:")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.startsWith("database.port:")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.startsWith("database.maxPoolSize:")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.startsWith("server.restPort:")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.startsWith("auth.keystorePassword:")), errors.toString());
	}

	@Test
	public void testExceptionMessageListsEveryError() {
		LoomOptions options = new LoomOptions();
		options.getDatabase().setPort(0);

		ConfigurationValidationException e = assertThrows(ConfigurationValidationException.class, options::validate);
		assertTrue(e.getMessage().startsWith("Configuration validation failed with 2 error(s):"), e.getMessage());
		for (String error : e.getErrors()) {
			assertTrue(e.getMessage().contains(error), "Message should contain {" + error + "} but was: " + e.getMessage());
		}
	}

	@Test
	public void testErrorMentionsEnvironmentVariable() {
		LoomOptions options = validOptions();
		options.getDatabase().setPort(0);
		assertTrue(errorsOf(options).get(0).endsWith("[env: LOOM_DB_PORT]"), "Expected an env hint but got: " + errorsOf(options).get(0));
	}

	@Test
	public void testErrorOmitsEnvHintForFieldsWithoutEnvVar() {
		LoomOptions options = validOptions();
		// acquireIncrement is deliberately not annotated with @EnvironmentVariable
		options.getDatabase().setAcquireIncrement(0);
		assertTrue(!errorsOf(options).get(0).contains("[env:"), "Did not expect an env hint: " + errorsOf(options).get(0));
	}

	@Test
	public void testValidationNeverLeaksSecretValues() {
		LoomOptions options = validOptions();
		options.getDatabase().setPassword("  ");
		assertTrue(!errorsOf(options).get(0).contains("  "), "Secret value must not appear in the message");
	}
}
