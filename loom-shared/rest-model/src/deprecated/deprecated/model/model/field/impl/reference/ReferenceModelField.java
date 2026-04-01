package deprecated.model.model.field.impl.reference;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import deprecated.model.model.field.AbstractModelField;
import deprecated.model.model.field.FieldType;

@JsonPropertyOrder({ "name", "type", "i18N", "indexing", "required" })
public class ReferenceModelField extends AbstractModelField {

	@JsonPropertyDescription("Specifies the allowed type of the element which can be referenced")
	private ReferenceType alllow;

	@Override
	public ReferenceModelField setName(String name) {
		super.setName(name);
		return this;
	}

	@Override
	public ReferenceModelField setI18N(Boolean i18n) {
		super.setI18N(i18n);
		return this;
	}

	@Override
	public ReferenceModelField setIndexing(Boolean indexing) {
		super.setIndexing(indexing);
		return this;
	}

	@Override
	public ReferenceModelField setRequired(Boolean required) {
		super.setRequired(required);
		return this;
	}

	@Override
	public FieldType getType() {
		return FieldType.REFERENCE;
	}

	public ReferenceType getAlllow() {
		return alllow;
	}

	public ReferenceModelField setAlllow(ReferenceType alllow) {
		this.alllow = alllow;
		return this;
	}

}
