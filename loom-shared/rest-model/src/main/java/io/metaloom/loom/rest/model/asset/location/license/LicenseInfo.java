package io.metaloom.loom.rest.model.asset.location.license;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class LicenseInfo {

	@JsonPropertyDescription("The name of the license")
	private String name;

	@JsonPropertyDescription("The version of the license")
	private String version;

	public String getName() {
		return name;
	}

	public LicenseInfo setName(String name) {
		this.name = name;
		return this;
	}

	public String getVersion() {
		return version;
	}

	public LicenseInfo setVersion(String version) {
		this.version = version;
		return this;
	}
}
