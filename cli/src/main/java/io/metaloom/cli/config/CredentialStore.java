package io.metaloom.cli.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.metaloom.cli.output.CliJson;

/**
 * Reads and writes {@code credentials.yml}.
 *
 * <p>Held separately from {@code cli.yml} so the config file stays safe to share, commit or
 * paste into a bug report while the tokens do not.</p>
 *
 * <p>The file is created {@code 0600} and the permissions are re-asserted on every write.
 * On read, a file that is group- or world-readable is <em>refused</em> rather than warned
 * about - the same rule ssh applies to private keys, and for the same reason: a bearer token
 * is a credential, and silently using one that everybody on the box can read teaches the
 * wrong lesson. Filesystems without POSIX permissions (e.g. Windows) skip the check.</p>
 */
public class CredentialStore {

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public static class Credentials {

		private String username;
		private String token;

		public String getUsername() {
			return username;
		}

		public Credentials setUsername(String username) {
			this.username = username;
			return this;
		}

		public String getToken() {
			return token;
		}

		public Credentials setToken(String token) {
			this.token = token;
			return this;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class CredentialsFile {

		private Map<String, Credentials> profiles = new LinkedHashMap<>();

		public Map<String, Credentials> getProfiles() {
			return profiles;
		}

		public CredentialsFile setProfiles(Map<String, Credentials> profiles) {
			this.profiles = profiles == null ? new LinkedHashMap<>() : profiles;
			return this;
		}
	}

	private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

	private final Path file;

	public CredentialStore(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	/**
	 * @param profile the profile name
	 * @return the stored credentials, or null when none are stored
	 * @throws InsecureCredentialsException when the file is readable by anyone but its owner
	 */
	public Credentials load(String profile) {
		// Strict: this hands a token to a caller who is about to send it somewhere.
		return loadFile(true).getProfiles().get(profile);
	}

	public CredentialsFile loadFile() {
		return loadFile(true);
	}

	/**
	 * @param strict whether to refuse a file that others can read
	 */
	private CredentialsFile loadFile(boolean strict) {
		if (!Files.exists(file)) {
			return new CredentialsFile();
		}
		if (strict) {
			assertNotWorldReadable();
		}
		try {
			String content = Files.readString(file);
			if (content.isBlank()) {
				return new CredentialsFile();
			}
			return CliJson.yaml().readValue(content, CredentialsFile.class);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	public void store(String profile, Credentials credentials) {
		// Lenient on the read-modify-write: refusing here would make a file that somehow
		// became world-readable impossible to fix, because `metaloom login` - the obvious
		// remedy - would be blocked by the very problem it repairs. The write below tightens
		// the permissions, so storing is how the file gets fixed.
		CredentialsFile all = loadFile(false);
		all.getProfiles().put(profile, credentials);
		write(all);
	}

	/** @return true if there were credentials to remove */
	public boolean remove(String profile) {
		CredentialsFile all = loadFile(false);
		boolean removed = all.getProfiles().remove(profile) != null;
		if (removed) {
			write(all);
		}
		return removed;
	}

	private void write(CredentialsFile all) {
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				createOwnerOnly();
			}
			Files.writeString(file, CliJson.yaml().writeValueAsString(all));
			// Re-assert after every write: the file may have been created before this
			// version, or loosened by hand.
			restrictPermissions();
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}

	private void createOwnerOnly() throws IOException {
		if (supportsPosix()) {
			// Create with the right mode rather than creating then chmod-ing: the gap between
			// the two is a window where the token is world-readable.
			Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
		} else {
			Files.createFile(file);
		}
	}

	private void restrictPermissions() throws IOException {
		if (supportsPosix()) {
			Files.setPosixFilePermissions(file, OWNER_ONLY);
		}
	}

	private boolean supportsPosix() {
		return Files.getFileAttributeView(file.getParent(), PosixFileAttributeView.class) != null;
	}

	private void assertNotWorldReadable() {
		if (!supportsPosix()) {
			return;
		}
		try {
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
			boolean exposed = permissions.contains(PosixFilePermission.GROUP_READ)
				|| permissions.contains(PosixFilePermission.OTHERS_READ)
				|| permissions.contains(PosixFilePermission.GROUP_WRITE)
				|| permissions.contains(PosixFilePermission.OTHERS_WRITE);
			if (exposed) {
				throw new InsecureCredentialsException(file, PosixFilePermissions.toString(permissions));
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not inspect permissions of " + file, e);
		}
	}

	/** Raised when the credentials file is readable by more than its owner. */
	public static class InsecureCredentialsException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public InsecureCredentialsException(Path file, String permissions) {
			super("Credentials file " + file + " has permissions " + permissions
				+ " and is readable by others. Run: chmod 600 " + file);
		}
	}
}
