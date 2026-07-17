package io.metaloom.cortex.node.ocr;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

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

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		
		// tessDataPath must not be empty
		if (tessDataPath == null || tessDataPath.isBlank()) {
			errors.add("tessDataPath must not be empty");
		}
		
		// language must not be empty
		if (language == null || language.isBlank()) {
			errors.add("language must not be empty");
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
