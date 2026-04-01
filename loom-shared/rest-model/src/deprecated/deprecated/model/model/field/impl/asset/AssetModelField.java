package deprecated.model.model.field.impl.asset;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import deprecated.model.model.field.AbstractModelField;
import deprecated.model.model.field.FieldType;

@JsonPropertyOrder({ "name", "type", "i18N", "indexing", "required" })
public class AssetModelField extends AbstractModelField {

	@Override
	public AssetModelField setName(String name) {
		super.setName(name);
		return this;
	}

	@Override
	public FieldType getType() {
		return FieldType.ASSET;
	}
}
