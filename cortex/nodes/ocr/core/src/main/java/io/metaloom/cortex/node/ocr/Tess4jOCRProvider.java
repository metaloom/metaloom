package io.metaloom.cortex.node.ocr;

import java.io.File;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

/**
 * OCR provider backed by Tesseract via tess4j.
 */
public class Tess4jOCRProvider implements OCRProvider {

	private final String datapath;

	/**
	 * @param datapath path to the tessdata directory (e.g. "/usr/share/tesseract-ocr/5/tessdata")
	 */
	public Tess4jOCRProvider(String datapath) {
		this.datapath = datapath;
	}

	@Override
	public String name() {
		return "tess4j";
	}

	@Override
	public String recognizeText(File imageFile, String language) throws OCRProviderException {
		try {
			Tesseract tesseract = new Tesseract();
			tesseract.setDatapath(datapath);
			tesseract.setLanguage(language);
			return tesseract.doOCR(imageFile);
		} catch (TesseractException e) {
			throw new OCRProviderException("Tesseract OCR failed for " + imageFile.getName(), e);
		}
	}
}
