package io.metaloom.cortex.node.ocr;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class OCRNodeOptions extends AbstractNodeOptions<OCRNodeOptions> {

	public static final String KEY = "ocr";

	@Override
	protected OCRNodeOptions self() {
		return this;
	}

}
