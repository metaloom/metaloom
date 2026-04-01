package deprecated.content.field.number;

import java.util.ArrayList;
import java.util.List;

import deprecated.content.field.AbstractListContentField;
import deprecated.model.model.field.FieldType;

public class NumberListContentField extends AbstractListContentField {

	private List<Number> items;

	@Override
	public FieldType getListType() {
		return FieldType.NUMBER;
	}

	@Override
	public NumberListContentField setName(String name) {
		super.setName(name);
		return this;
	}

	public List<Number> getItems() {
		return items;
	}

	public NumberListContentField setItems(List<Number> items) {
		this.items = items;
		return this;
	}

	public NumberListContentField addNumber(Number number) {
		if (items == null) {
			items = new ArrayList<>();
		}
		items.add(number);
		return this;
	}
}
