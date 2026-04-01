package deprecated.content.field.text;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import deprecated.content.field.AbstractContentField;
import deprecated.model.model.field.FieldType;

public class TextContentField extends AbstractContentField {

	@JsonPropertyDescription("The text value of the field.")
	private String value;

	public String getValue() {
		return value;
	}

	public TextContentField setValue(String text) {
		this.value = text;
		return this;
	}

	@Override
	public TextContentField setName(String name) {
		super.setName(name);
		return this;
	}

	@Override
	public FieldType getType() {
		return FieldType.TEXT;
	}
}
