package deprecated.content.field;

import deprecated.model.model.field.FieldType;

public abstract class AbstractListContentField extends AbstractContentField {

	abstract public FieldType getListType();

	@Override
	public FieldType getType() {
		return FieldType.LIST;
	}
}
