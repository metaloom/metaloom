package deprecated.model.model.field;

public enum FieldType {

	/**
	 * Field which contains text content
	 */
	TEXT,

	/**
	 * Field which contains a number
	 */
	NUMBER,

	/**
	 * Field which contains a boolean
	 */
	BOOLEAN,

	/**
	 * Field which contains a date
	 */
	DATE,

	/**
	 * Field which contains a list
	 */
	LIST,

	/**
	 * Field which contains a reference to an asset
	 */
	ASSET,

	/**
	 * Field which contains JSON
	 */
	JSON,

	/**
	 * Field which contains a set of nested fields
	 */
	NESTED,
	
	/**
	 * Field which contains a reference to a content element.
	 */
	REFERENCE;
}
