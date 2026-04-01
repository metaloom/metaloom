package deprecated.content.field.reference;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import deprecated.content.field.AbstractContentField;
import deprecated.model.model.field.FieldType;

public class ReferenceContentField extends AbstractContentField {

	@JsonPropertyDescription("Reference to the element.")
	private UUID uuid;

	@Override
	public ReferenceContentField setName(String name) {
		super.setName(name);
		return this;
	}

	@Override
	public FieldType getType() {
		return FieldType.REFERENCE;
	}

	public UUID getUuid() {
		return uuid;
	}

	public ReferenceContentField setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}
}
