package io.metaloom.loom.rest.model.dbintegrity;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * One entry of the integrity check catalogue: what a check looks for, independent of whether it has
 * been run.
 */
public class DbIntegrityCheckModel implements RestResponseModel<DbIntegrityCheckModel> {

	@JsonPropertyDescription("Stable identifier of the check, SCREAMING_SNAKE_CASE. The only field a client should branch on; the name and description may be reworded.")
	private String code;

	@JsonPropertyDescription("Short human-readable label naming what the check looks at, for display in a catalogue or report.")
	private String name;

	@JsonPropertyDescription("What kind of defect this looks for: DANGLING, TIMESTAMP, MANDATORY_FIELD, VOCABULARY or CARDINALITY.")
	private String category;

	@JsonPropertyDescription("How badly a finding matters: ERROR (data the application will misread or crash on), WARN (a human should judge) or INFO.")
	private String severity;

	@JsonPropertyDescription("The table the check reads. Names the theme rather than one table where a check sweeps several.")
	private String table;

	@JsonPropertyDescription("The column the check reads, or null when the check is about whole rows.")
	private String column;

	@JsonPropertyDescription("One sentence saying what a finding means and why it is bad.")
	private String description;

	public DbIntegrityCheckModel() {
	}

	public String getCode() {
		return code;
	}

	public DbIntegrityCheckModel setCode(String code) {
		this.code = code;
		return this;
	}

	public String getName() {
		return name;
	}

	public DbIntegrityCheckModel setName(String name) {
		this.name = name;
		return this;
	}

	public String getCategory() {
		return category;
	}

	public DbIntegrityCheckModel setCategory(String category) {
		this.category = category;
		return this;
	}

	public String getSeverity() {
		return severity;
	}

	public DbIntegrityCheckModel setSeverity(String severity) {
		this.severity = severity;
		return this;
	}

	public String getTable() {
		return table;
	}

	public DbIntegrityCheckModel setTable(String table) {
		this.table = table;
		return this;
	}

	public String getColumn() {
		return column;
	}

	public DbIntegrityCheckModel setColumn(String column) {
		this.column = column;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public DbIntegrityCheckModel setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public DbIntegrityCheckModel self() {
		return this;
	}
}
