package deprecated.content.field.text;

import deprecated.content.field.AbstractListContentField;
import deprecated.model.model.field.FieldType;

public class TextListContentField extends AbstractListContentField {

	@Override
	public FieldType getListType() {
		return FieldType.TEXT;
	}

}
