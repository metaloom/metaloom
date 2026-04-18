package io.metaloom.cortex.node.ocr;

/**
 * Exception thrown when an {@link OCRProvider} fails to process an image.
 */
public class OCRProviderException extends Exception {

	private static final long serialVersionUID = 1L;

	public OCRProviderException(String message) {
		super(message);
	}

	public OCRProviderException(String message, Throwable cause) {
		super(message, cause);
	}
}
