package deprecated.model.model.field;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public abstract class AbstractModelField implements ModelField {

	@JsonPropertyDescription("Name of the field by which it will be referenced in the content.")
	private String name;

	@JsonPropertyDescription("Flag which indicates whether the field should be required in a content.")
	private Boolean required;

	@JsonPropertyDescription("Flag which indicates whether it should be supported to translate the field.")
	private Boolean i18n;

	@JsonPropertyDescription("Flag which indicates whether contents should include this field in the search index.")
	private Boolean indexing;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public ModelField setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public Boolean getRequired() {
		return required;
	}

	@Override
	public ModelField  setRequired(Boolean required) {
		this.required = required;
		return this;
	}

	@Override
	public Boolean getI18N() {
		return this.i18n;
	}

	@Override
	public ModelField setI18N(Boolean i18n) {
		this.i18n = i18n;
		return this;
	}

	@Override
	public Boolean getIndexing() {
		return this.indexing;
	}

	@Override
	public ModelField setIndexing(Boolean indexing) {
		this.indexing = indexing;
		return this;
	}

}
