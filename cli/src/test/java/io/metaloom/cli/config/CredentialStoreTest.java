package io.metaloom.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cli.config.CredentialStore.Credentials;

/**
 * A bearer token is a credential; the file permissions are part of the contract.
 */
public class CredentialStoreTest {

	@TempDir
	Path tempDir;

	private CredentialStore store() {
		return new CredentialStore(tempDir.resolve("credentials.yml"));
	}

	private boolean posix() {
		return Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null;
	}

	@Test
	@DisplayName("a stored token round-trips")
	void testRoundTrip() {
		CredentialStore store = store();
		store.store("default", new Credentials().setUsername("admin").setToken("abc123"));

		Credentials loaded = store.load("default");

		assertThat(loaded).isNotNull();
		assertThat(loaded.getUsername()).isEqualTo("admin");
		assertThat(loaded.getToken()).isEqualTo("abc123");
	}

	@Test
	@DisplayName("profiles are stored independently")
	void testMultipleProfiles() {
		CredentialStore store = store();
		store.store("dev", new Credentials().setToken("dev-token"));
		store.store("prod", new Credentials().setToken("prod-token"));

		assertThat(store.load("dev").getToken()).isEqualTo("dev-token");
		assertThat(store.load("prod").getToken()).isEqualTo("prod-token");
	}

	@Test
	@DisplayName("loading from a missing file yields nothing rather than failing")
	void testMissingFile() {
		assertThat(store().load("default")).isNull();
	}

	@Test
	@DisplayName("logout removes only the named profile")
	void testRemove() {
		CredentialStore store = store();
		store.store("dev", new Credentials().setToken("dev-token"));
		store.store("prod", new Credentials().setToken("prod-token"));

		assertThat(store.remove("dev")).isTrue();

		assertThat(store.load("dev")).isNull();
		assertThat(store.load("prod")).isNotNull();
		assertThat(store.remove("dev")).as("removing again reports nothing was there").isFalse();
	}

	@Test
	@DisplayName("the credentials file is created readable only by its owner")
	void testCreatedOwnerOnly() throws Exception {
		assumeTrue(posix(), "POSIX permissions are not available on this filesystem");
		CredentialStore store = store();

		store.store("default", new Credentials().setToken("secret"));

		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())))
			.isEqualTo("rw-------");
	}

	@Test
	@DisplayName("logging in again repairs a loosened file rather than being blocked by it")
	void testStoreTightensLoosenedFile() throws Exception {
		assumeTrue(posix(), "POSIX permissions are not available on this filesystem");
		CredentialStore store = store();
		store.store("default", new Credentials().setToken("secret"));
		Files.setPosixFilePermissions(store.file(), PosixFilePermissions.fromString("rw-r--r--"));

		// A write must not be refused by the read guard: `metaloom login` is the obvious way
		// to fix this state, and blocking it would leave the user stuck.
		store.store("default", new Credentials().setToken("secret2"));

		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())))
			.isEqualTo("rw-------");
		assertThat(store.load("default").getToken()).isEqualTo("secret2");
	}

	@Test
	@DisplayName("logout also repairs a loosened file")
	void testRemoveOnLoosenedFile() throws Exception {
		assumeTrue(posix(), "POSIX permissions are not available on this filesystem");
		CredentialStore store = store();
		store.store("default", new Credentials().setToken("secret"));
		Files.setPosixFilePermissions(store.file(), PosixFilePermissions.fromString("rw-r--r--"));

		assertThat(store.remove("default")).isTrue();

		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())))
			.isEqualTo("rw-------");
	}

	@Test
	@DisplayName("a world-readable credentials file is refused, not merely warned about")
	void testWorldReadableRefused() throws Exception {
		assumeTrue(posix(), "POSIX permissions are not available on this filesystem");
		CredentialStore store = store();
		store.store("default", new Credentials().setToken("secret"));
		Files.setPosixFilePermissions(store.file(), PosixFilePermissions.fromString("rw-r--r--"));

		// The ssh rule. Silently using a token everyone on the box can read teaches the wrong
		// lesson, and the fix is one chmod away - so say so.
		assertThatThrownBy(() -> store.load("default"))
			.isInstanceOf(CredentialStore.InsecureCredentialsException.class)
			.hasMessageContaining("chmod 600");
	}

	@Test
	@DisplayName("a group-writable credentials file is refused too")
	void testGroupWritableRefused() throws Exception {
		assumeTrue(posix(), "POSIX permissions are not available on this filesystem");
		CredentialStore store = store();
		store.store("default", new Credentials().setToken("secret"));
		Files.setPosixFilePermissions(store.file(), PosixFilePermissions.fromString("rw-rw----"));

		assertThatThrownBy(() -> store.load("default"))
			.isInstanceOf(CredentialStore.InsecureCredentialsException.class);
	}

	@Test
	@DisplayName("an empty credentials file is treated as no credentials")
	void testEmptyFile() throws Exception {
		CredentialStore store = store();
		Files.createDirectories(tempDir);
		Files.writeString(store.file(), "");
		if (posix()) {
			Files.setPosixFilePermissions(store.file(), PosixFilePermissions.fromString("rw-------"));
		}

		assertThat(store.load("default")).isNull();
	}
}
