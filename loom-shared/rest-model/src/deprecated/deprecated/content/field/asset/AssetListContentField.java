package deprecated.content.field.asset;

import java.util.ArrayList;
import java.util.List;

import deprecated.content.field.AbstractListContentField;
import deprecated.model.model.field.FieldType;

public class AssetListContentField extends AbstractListContentField {

	private List<AssetContentField> fields;

	@Override
	public FieldType getListType() {
		return FieldType.ASSET;
	}

	public List<AssetContentField> getFields() {
		return fields;
	}

	public AssetListContentField setFields(List<AssetContentField> fields) {
		this.fields = fields;
		return this;
	}

	public AssetListContentField addField(AssetContentField field) {
		if (fields == null) {
			fields = new ArrayList<>();
		}
		fields.add(field);
		return this;
	}

}
