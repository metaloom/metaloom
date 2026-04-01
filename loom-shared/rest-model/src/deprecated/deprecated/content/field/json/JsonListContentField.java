package deprecated.content.field.json;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import deprecated.content.field.AbstractListContentField;
import deprecated.model.model.field.FieldType;

public class JsonListContentField extends AbstractListContentField {

	private List<JsonNode> items;

	@Override
	public FieldType getListType() {
		return FieldType.JSON;
	}

//	@Override
//	public JsonListContentField setI18N(Boolean i18n) {
//		super.setI18N(i18n);
//		return this;
//	}
//
//	@Override
//	public JsonListContentField setName(String name) {
//		super.setName(name);
//		return this;
//	}
//
//	@Override
//	public JsonListContentField setIndexing(Boolean indexing) {
//		super.setIndexing(indexing);
//		return this;
//	}
//
//	@Override
//	public JsonListContentField setRequired(Boolean required) {
//		super.setRequired(required);
//		return this;
//	}

	public List<JsonNode> getItems() {
		return items;
	}

	public JsonListContentField setItems(List<JsonNode> items) {
		this.items = items;
		return this;
	}

	public JsonListContentField addItem(JsonNode node) {
		if (items == null) {
			items = new ArrayList<>();
		}
		items.add(node);
		return this;
	}
}
