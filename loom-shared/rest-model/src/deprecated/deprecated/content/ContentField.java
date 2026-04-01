package deprecated.content;

import deprecated.model.model.field.FieldType;
import io.metaloom.loom.rest.model.RestModel;

public interface ContentField extends RestModel {

	/**
	 * Return the model type of the content field.
	 * 
	 * @return
	 */
	FieldType getType();

	/**
	 * Return corresponding model name for the field.
	 * 
	 * @return
	 */
	String getName();

	/**
	 * Set the corresponding model name for the field.
	 * 
	 * @param name
	 * @return
	 */
	ContentField setName(String name);

}
