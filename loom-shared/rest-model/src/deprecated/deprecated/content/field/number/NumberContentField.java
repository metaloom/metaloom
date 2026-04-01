package deprecated.content.field.number;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import deprecated.content.field.AbstractContentField;
import deprecated.model.model.field.FieldType;

public class NumberContentField extends AbstractContentField {

	@JsonPropertyDescription("The number value of the field.")
	private Number value;

	@Override
	public NumberContentField setName(String name) {
		super.setName(name);
		return this;
	}

	@Override
	public FieldType getType() {
		return FieldType.NUMBER;
	}

	public Number getValue() {
		return value;
	}

	public NumberContentField setValue(Number value) {
		this.value = value;
		return this;
	}
}
