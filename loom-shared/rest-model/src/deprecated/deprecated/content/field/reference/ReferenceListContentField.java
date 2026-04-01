package deprecated.content.field.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import deprecated.content.ContentField;
import deprecated.content.field.AbstractListContentField;
import deprecated.model.model.field.FieldType;

public class ReferenceListContentField extends AbstractListContentField {

	private List<UUID> items;

	@Override
	public FieldType getListType() {
		return FieldType.REFERENCE;
	}

	@Override
	public ContentField setName(String name) {
		super.setName(name);
		return this;
	}

	public List<UUID> getItems() {
		return items;
	}

	public ReferenceListContentField setItems(List<UUID> items) {
		this.items = items;
		return this;
	}

	public ReferenceListContentField addReference(UUID uuid) {
		if (items == null) {
			items = new ArrayList<>();
		}
		items.add(uuid);
		return this;
	}

}
