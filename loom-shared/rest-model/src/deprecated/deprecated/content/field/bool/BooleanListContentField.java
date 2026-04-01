package deprecated.content.field.bool;

import deprecated.content.field.AbstractListContentField;
import deprecated.model.model.field.FieldType;

public class BooleanListContentField extends AbstractListContentField {

	@Override
	public FieldType getListType() {
		return FieldType.BOOLEAN;
	}

}
