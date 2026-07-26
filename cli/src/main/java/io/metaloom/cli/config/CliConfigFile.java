package io.metaloom.cli.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The on-disk shape of {@code cli.yml}.
 *
 * <p>Unknown properties are ignored so a config written by a newer CLI does not break an
 * older one.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CliConfigFile {

	public static final String DEFAULT_PROFILE = "default";

	private String currentProfile = DEFAULT_PROFILE;
	private Map<String, Profile> profiles = new LinkedHashMap<>();

	public String getCurrentProfile() {
		return currentProfile == null || currentProfile.isBlank() ? DEFAULT_PROFILE : currentProfile;
	}

	public CliConfigFile setCurrentProfile(String currentProfile) {
		this.currentProfile = currentProfile;
		return this;
	}

	public Map<String, Profile> getProfiles() {
		return profiles;
	}

	public CliConfigFile setProfiles(Map<String, Profile> profiles) {
		this.profiles = profiles == null ? new LinkedHashMap<>() : profiles;
		return this;
	}

	/** @return the named profile, creating an empty one if it does not exist yet */
	public Profile profile(String name) {
		return profiles.computeIfAbsent(name, key -> new Profile());
	}

	/** @return the named profile, or null */
	public Profile find(String name) {
		return profiles.get(name);
	}
}
