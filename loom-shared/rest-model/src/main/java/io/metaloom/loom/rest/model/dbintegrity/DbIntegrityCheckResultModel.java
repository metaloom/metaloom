package io.metaloom.loom.rest.model.dbintegrity;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * What one check found.
 *
 * <p>
 * A count plus a capped sample, never the offending rows themselves - a broken trigger can leave
 * six-figure orphan counts, and a JSON response is not the place to carry them.
 * </p>
 */
public class DbIntegrityCheckResultModel implements RestResponseModel<DbIntegrityCheckResultModel> {

	@JsonPropertyDescription("The catalogue entry for this check, so a result is readable without a second lookup.")
	private DbIntegrityCheckModel check;

	@JsonPropertyDescription("Number of offending rows. Zero means the check passed.")
	private long count;

	@JsonPropertyDescription("Up to `limit` of the offending rows, rendered as 'uuid (column=value, ...)'. Empty when count is zero.")
	private List<String> samples = new ArrayList<>();

	@JsonPropertyDescription("Wall time this check took, in milliseconds.")
	private long durationMs;

	@JsonPropertyDescription("Set only when the check itself threw - a dropped column after a migration, say. One broken check does not stop the sweep, so it is reported here and the rest still ran.")
	private String error;

	public DbIntegrityCheckResultModel() {
	}

	public DbIntegrityCheckModel getCheck() {
		return check;
	}

	public DbIntegrityCheckResultModel setCheck(DbIntegrityCheckModel check) {
		this.check = check;
		return this;
	}

	public long getCount() {
		return count;
	}

	public DbIntegrityCheckResultModel setCount(long count) {
		this.count = count;
		return this;
	}

	public List<String> getSamples() {
		return samples;
	}

	public DbIntegrityCheckResultModel setSamples(List<String> samples) {
		this.samples = samples;
		return this;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public DbIntegrityCheckResultModel setDurationMs(long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	public String getError() {
		return error;
	}

	public DbIntegrityCheckResultModel setError(String error) {
		this.error = error;
		return this;
	}

	@Override
	public DbIntegrityCheckResultModel self() {
		return this;
	}
}
