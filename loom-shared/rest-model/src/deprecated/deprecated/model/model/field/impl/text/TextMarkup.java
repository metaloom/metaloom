package deprecated.model.model.field.impl.text;

public enum TextMarkup {

	/**
	 * No markup specified. No processing will be applied.
	 */
	PLAIN,

	/**
	 * Text contains HTML. Markup processing may be applied when storing values in search index.
	 */
	HTML,

	/**
	 * Content is formatted as markdown. Conversion and rendering will be applied.
	 */
	MARKDOWN;

}
