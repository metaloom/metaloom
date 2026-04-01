package deprecated.content.field.nested;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import deprecated.content.ContentField;
import deprecated.content.field.AbstractContentField;
import deprecated.model.model.field.FieldType;

public class NestedContentField extends AbstractContentField {

	@JsonPropertyDescription("The list of nested fields.")
	private List<ContentField> fields;

	@Override
	public NestedContentField setName(String name) {
		super.setName(name);
		return this;
	}

	@Override
	public FieldType getType() {
		return FieldType.NESTED;
	}

	public List<ContentField> getFields() {
		return fields;
	}

	public NestedContentField setFields(List<ContentField> fields) {
		this.fields = fields;
		return this;
	}

}
