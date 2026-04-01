package deprecated.content.field;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import deprecated.content.ContentField;

public abstract class AbstractContentField implements ContentField {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The name of the field that was specified in the model.")
	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public ContentField setName(String name) {
		this.name = name;
		return this;
	}

}
