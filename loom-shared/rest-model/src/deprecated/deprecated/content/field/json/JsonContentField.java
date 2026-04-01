package deprecated.content.field.json;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;

import deprecated.content.field.AbstractContentField;
import deprecated.model.model.field.FieldType;

public class JsonContentField extends AbstractContentField {

	@JsonPropertyDescription("The JSON object for the field.")
	private JsonNode json;

	@Override
	public JsonContentField setName(String name) {
		super.setName(name);
		return this;
	}

	@Override
	public FieldType getType() {
		return FieldType.JSON;
	}

	public JsonNode getJson() {
		return json;
	}

	public JsonContentField setJson(JsonNode json) {
		this.json = json;
		return this;
	}
}
