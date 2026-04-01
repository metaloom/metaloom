package deprecated.model.model.field.impl.text;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import deprecated.model.model.field.AbstractModelField;
import deprecated.model.model.field.FieldType;

@JsonPropertyOrder({ "name", "type", "i18N", "indexing", "required" })
public class TextModelField extends AbstractModelField {

	private TextMarkup markup;

	@Override
	public TextModelField setName(String name) {
		super.setName(name);
		return this;
	}

	@Override
	public TextModelField setI18N(Boolean i18n) {
		super.setI18N(i18n);
		return this;
	}

	@Override
	public TextModelField setIndexing(Boolean indexing) {
		super.setIndexing(indexing);
		return this;
	}

	@Override
	public TextModelField setRequired(Boolean required) {
		super.setRequired(required);
		return this;
	}

	@Override
	public FieldType getType() {
		return FieldType.TEXT;
	}

	public TextMarkup getMarkup() {
		return markup;
	}

	public TextModelField setMarkup(TextMarkup markup) {
		this.markup = markup;
		return this;
	}

}
