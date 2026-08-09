package io.metaloom.loom.rest.model.dbintegrity;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The result of one sweep of the database integrity checks.
 *
 * <p>
 * Computed on request - there is no stored report and no job to poll. Clean checks are included on
 * purpose: "29 checks ran and found nothing" is a more useful answer than an empty list, and it is
 * what lets the admin screen show what was actually looked at.
 * </p>
 */
public class DbIntegrityReportResponse implements RestResponseModel<DbIntegrityReportResponse> {

	@JsonPropertyDescription("Server time the sweep started (ISO 8601 instant).")
	private String timestamp;

	@JsonPropertyDescription("Wall time of the whole sweep, in milliseconds.")
	private long durationMs;

	@JsonPropertyDescription("Number of checks that ran under the requested filters.")
	private int checksRun;

	@JsonPropertyDescription("Total offending rows across every check.")
	private long findingCount;

	@JsonPropertyDescription("True when no check found anything and none failed to run.")
	private boolean clean;

	@JsonPropertyDescription("One entry per check that ran, in catalogue order, whether it found anything or not.")
	private List<DbIntegrityCheckResultModel> results = new ArrayList<>();

	public DbIntegrityReportResponse() {
	}

	public String getTimestamp() {
		return timestamp;
	}

	public DbIntegrityReportResponse setTimestamp(String timestamp) {
		this.timestamp = timestamp;
		return this;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public DbIntegrityReportResponse setDurationMs(long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	public int getChecksRun() {
		return checksRun;
	}

	public DbIntegrityReportResponse setChecksRun(int checksRun) {
		this.checksRun = checksRun;
		return this;
	}

	public long getFindingCount() {
		return findingCount;
	}

	public DbIntegrityReportResponse setFindingCount(long findingCount) {
		this.findingCount = findingCount;
		return this;
	}

	public boolean isClean() {
		return clean;
	}

	public DbIntegrityReportResponse setClean(boolean clean) {
		this.clean = clean;
		return this;
	}

	public List<DbIntegrityCheckResultModel> getResults() {
		return results;
	}

	public DbIntegrityReportResponse setResults(List<DbIntegrityCheckResultModel> results) {
		this.results = results;
		return this;
	}

	public DbIntegrityReportResponse add(DbIntegrityCheckResultModel result) {
		this.results.add(result);
		return this;
	}

	@Override
	public DbIntegrityReportResponse self() {
		return this;
	}
}
