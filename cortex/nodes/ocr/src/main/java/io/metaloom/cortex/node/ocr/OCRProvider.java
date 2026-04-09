package io.metaloom.cortex.node.ocr;

import java.io.File;

/**
 * Pluggable OCR provider. Implementations wrap a specific OCR engine
 * (e.g. Tesseract via tess4j, EasyOCR, cloud APIs).
 */
public interface OCRProvider {

	/**
	 * Unique name of this provider (e.g. "tess4j", "easyocr").
	 */
	String name();

	/**
	 * Run OCR on the given image file and return the extracted text.
	 *
	 * @param imageFile the image to process
	 * @param language  BCP-47 / ISO-639 language hint (e.g. "eng", "deu")
	 * @return the recognised text, never null (empty string if nothing detected)
	 * @throws OCRProviderException if OCR processing fails
	 */
	String recognizeText(File imageFile, String language) throws OCRProviderException;
}
