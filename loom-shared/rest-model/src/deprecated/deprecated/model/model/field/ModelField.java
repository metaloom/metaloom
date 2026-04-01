package deprecated.model.model.field;

import io.metaloom.loom.rest.model.RestModel;

public interface ModelField extends RestModel {

	/**
	 * Return the name of the field within the model.
	 * 
	 * @return
	 */
	String getName();

	/**
	 * Set the name of the model.
	 * 
	 * @param name
	 * @return
	 */
	ModelField setName(String name);

	/**
	 * Return the flag which indicates whether the field is required.
	 * 
	 * @return
	 */
	Boolean getRequired();

	/**
	 * Set the required flag for the field.
	 * 
	 * @param required
	 * @return
	 */
	ModelField setRequired(Boolean required);

	/**
	 * Return the type of the field.
	 * 
	 * @return
	 */
	FieldType getType();

	/**
	 * Return the flag which indicates whether the field should be translatable.
	 * 
	 * @return
	 */
	Boolean getI18N();

	/**
	 * Set the flag which indicates whether the field should be translatable.
	 * 
	 * @param i18n
	 * @return
	 */
	ModelField setI18N(Boolean i18n);

	/**
	 * Should the field be included in the search index?
	 * 
	 * @return
	 */
	Boolean getIndexing();

	/**
	 * Configure whether the field should be included in the search index.
	 * 
	 * @param indexing
	 * @return
	 */
	ModelField setIndexing(Boolean indexing);

}
