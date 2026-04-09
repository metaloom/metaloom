package io.metaloom.cortex.node.ocr;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class OCRNodeOptions extends AbstractNodeOptions<OCRNodeOptions> {

	public static final String KEY = "ocr";

	private String tessDataPath = "/usr/share/tesseract-ocr/5/tessdata";

	private String language = "eng";

	@Override
	protected OCRNodeOptions self() {
		return this;
	}

	public String getTessDataPath() {
		return tessDataPath;
	}

	public OCRNodeOptions setTessDataPath(String tessDataPath) {
		this.tessDataPath = tessDataPath;
		return self();
	}

	public String getLanguage() {
		return language;
	}

	public OCRNodeOptions setLanguage(String language) {
		this.language = language;
		return self();
	}
}
