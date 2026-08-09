package io.metaloom.loom.rest.model.dbintegrity;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The catalogue of registered integrity checks, with nothing run.
 *
 * <p>
 * Separate from the report so a caller can find out what exists - to build a filter, or to render
 * descriptions for checks that passed - without paying for a full sweep.
 * </p>
 */
public class DbIntegrityCheckListResponse implements RestResponseModel<DbIntegrityCheckListResponse> {

	@JsonPropertyDescription("Every registered check, in the order a report presents them.")
	private List<DbIntegrityCheckModel> checks = new ArrayList<>();

	public DbIntegrityCheckListResponse() {
	}

	public List<DbIntegrityCheckModel> getChecks() {
		return checks;
	}

	public DbIntegrityCheckListResponse setChecks(List<DbIntegrityCheckModel> checks) {
		this.checks = checks;
		return this;
	}

	public DbIntegrityCheckListResponse add(DbIntegrityCheckModel check) {
		this.checks.add(check);
		return this;
	}

	@Override
	public DbIntegrityCheckListResponse self() {
		return this;
	}
}
