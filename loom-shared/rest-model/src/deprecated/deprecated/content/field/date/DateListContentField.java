package deprecated.content.field.date;

import java.util.ArrayList;
import java.util.List;

import deprecated.content.field.AbstractListContentField;
import deprecated.model.model.field.FieldType;

public class DateListContentField extends AbstractListContentField {

	private List<String> items;

	@Override
	public FieldType getListType() {
		return FieldType.DATE;
	}

	@Override
	public DateListContentField setName(String name) {
		super.setName(name);
		return this;
	}

	public List<String> getItems() {
		return items;
	}

	public DateListContentField setItems(List<String> items) {
		this.items = items;
		return this;
	}

	public DateListContentField addDate(String date) {
		if (items == null) {
			items = new ArrayList<>();
		}
		items.add(date);
		return this;
	}

}
