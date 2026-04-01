package deprecated.model.model.field.impl.reference;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import deprecated.model.model.field.AbstractListModelField;
import deprecated.model.model.field.FieldType;

@JsonPropertyOrder({ "name", "type", "i18N", "indexing", "required" })
public class ReferenceListModelField extends AbstractListModelField {

	@Override
	public ReferenceListModelField setName(String name) {
		super.setName(name);
		return this;
	}

	@Override
	public ReferenceListModelField setI18N(Boolean i18n) {
		super.setI18N(i18n);
		return this;
	}

	@Override
	public ReferenceListModelField setIndexing(Boolean indexing) {
		super.setIndexing(indexing);
		return this;
	}

	@Override
	public ReferenceListModelField setRequired(Boolean required) {
		super.setRequired(required);
		return this;
	}

	@Override
	public FieldType getListType() {
		return FieldType.REFERENCE;
	}

}
