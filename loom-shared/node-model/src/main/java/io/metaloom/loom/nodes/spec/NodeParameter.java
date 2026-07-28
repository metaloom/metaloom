package io.metaloom.loom.nodes.spec;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Describes a configurable parameter of a pipeline node.
 * Used by the UI to generate edit forms.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeParameter {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Parameter key (maps to the options field name)")
	private String key;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Data type of this parameter")
	private ParameterType type;

	@JsonPropertyDescription("Default value")
	private Object defaultValue;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Human-readable label for the form field")
	private String label;

	@JsonPropertyDescription("Help text describing the parameter")
	private String description;

	@JsonPropertyDescription("Minimum value (for integer/number types)")
	private Number min;

	@JsonPropertyDescription("Maximum value (for integer/number types)")
	private Number max;

	@JsonPropertyDescription("Step increment (for number types)")
	private Number step;

	@JsonPropertyDescription("Allowed values (for enum/enum-set types)")
	private List<String> values;

	@JsonPropertyDescription("Syntax-highlighting hint for CODE parameters (e.g. 'javascript', 'groovy')")
	private String language;

	@JsonPropertyDescription("Preferred editor height in rows (for code/json types)")
	private Integer rows;

	public NodeParameter() {
	}

	public String getLanguage() {
		return language;
	}

	public NodeParameter setLanguage(String language) {
		this.language = language;
		return this;
	}

	public Integer getRows() {
		return rows;
	}

	public NodeParameter setRows(Integer rows) {
		this.rows = rows;
		return this;
	}

	public String getKey() {
		return key;
	}

	public NodeParameter setKey(String key) {
		this.key = key;
		return this;
	}

	public ParameterType getType() {
		return type;
	}

	public NodeParameter setType(ParameterType type) {
		this.type = type;
		return this;
	}

	public Object getDefaultValue() {
		return defaultValue;
	}

	public NodeParameter setDefaultValue(Object defaultValue) {
		this.defaultValue = defaultValue;
		return this;
	}

	public String getLabel() {
		return label;
	}

	public NodeParameter setLabel(String label) {
		this.label = label;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public NodeParameter setDescription(String description) {
		this.description = description;
		return this;
	}

	public Number getMin() {
		return min;
	}

	public NodeParameter setMin(Number min) {
		this.min = min;
		return this;
	}

	public Number getMax() {
		return max;
	}

	public NodeParameter setMax(Number max) {
		this.max = max;
		return this;
	}

	public Number getStep() {
		return step;
	}

	public NodeParameter setStep(Number step) {
		this.step = step;
		return this;
	}

	public List<String> getValues() {
		return values;
	}

	public NodeParameter setValues(List<String> values) {
		this.values = values;
		return this;
	}
}
