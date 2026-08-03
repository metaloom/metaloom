package io.metaloom.cortex.node.metadata;

import io.vertx.core.json.JsonObject;

/**
 * Licence and rights, gathered from XMP {@code xmpRights:}, Creative Commons {@code cc:}, IPTC and
 * EXIF.
 *
 * <p>
 * {@link #licenseId} is an SPDX-style identifier and is <b>only</b> set when {@link #licenseUrl}
 * matched a well-known licence URL exactly ({@link LicenseResolver}). A licence is never inferred
 * from free text: a wrong {@code CC-BY} on an all-rights-reserved photo is worse than no value at
 * all, because it is the value a "what may I republish" query trusts.
 * </p>
 */
public class RightsBlock {

	private String statement;
	private String holder;
	private String licenseUrl;
	private String licenseId;
	private String usageTerms;
	private String credit;
	private String webStatement;
	private Boolean marked;

	public String getStatement() {
		return statement;
	}

	public RightsBlock setStatement(String statement) {
		this.statement = statement;
		return this;
	}

	public String getHolder() {
		return holder;
	}

	public RightsBlock setHolder(String holder) {
		this.holder = holder;
		return this;
	}

	public String getLicenseUrl() {
		return licenseUrl;
	}

	public RightsBlock setLicenseUrl(String licenseUrl) {
		this.licenseUrl = licenseUrl;
		return this;
	}

	public String getLicenseId() {
		return licenseId;
	}

	public RightsBlock setLicenseId(String licenseId) {
		this.licenseId = licenseId;
		return this;
	}

	public String getUsageTerms() {
		return usageTerms;
	}

	public RightsBlock setUsageTerms(String usageTerms) {
		this.usageTerms = usageTerms;
		return this;
	}

	public String getCredit() {
		return credit;
	}

	public RightsBlock setCredit(String credit) {
		this.credit = credit;
		return this;
	}

	public String getWebStatement() {
		return webStatement;
	}

	public RightsBlock setWebStatement(String webStatement) {
		this.webStatement = webStatement;
		return this;
	}

	public Boolean getMarked() {
		return marked;
	}

	public RightsBlock setMarked(Boolean marked) {
		this.marked = marked;
		return this;
	}

	public boolean isEmpty() {
		return statement == null && holder == null && licenseUrl == null && licenseId == null
			&& usageTerms == null && credit == null && webStatement == null && marked == null;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		Envelopes.putIfPresent(json, "statement", statement);
		Envelopes.putIfPresent(json, "holder", holder);
		Envelopes.putIfPresent(json, "licenseUrl", licenseUrl);
		Envelopes.putIfPresent(json, "licenseId", licenseId);
		Envelopes.putIfPresent(json, "usageTerms", usageTerms);
		Envelopes.putIfPresent(json, "credit", credit);
		Envelopes.putIfPresent(json, "webStatement", webStatement);
		Envelopes.putIfPresent(json, "marked", marked);
		return json;
	}
}
