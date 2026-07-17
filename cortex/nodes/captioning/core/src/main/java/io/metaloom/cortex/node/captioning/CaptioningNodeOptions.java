package io.metaloom.cortex.node.captioning;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class CaptioningNodeOptions extends AbstractNodeOptions<CaptioningNodeOptions> {

	private int smolVLMPort = 8000;
	private String smolVLMHost = "localhost";

	@Override
	protected CaptioningNodeOptions self() {
		return this;
	}

	public int getSmolVLMPort() {
		return smolVLMPort;
	}

	public void setSmolVLMPort(int smolVLMPort) {
		this.smolVLMPort = smolVLMPort;
	}

	public String getSmolVLMHost() {
		return smolVLMHost;
	}

	public void setSmolVLMHost(String smolVLMHost) {
		this.smolVLMHost = smolVLMHost;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		
		// smolVLMHost must not be empty
		if (smolVLMHost == null || smolVLMHost.isBlank()) {
			errors.add("smolVLMHost must not be empty");
		}
		
		// smolVLMPort must be positive
		if (smolVLMPort <= 0) {
			errors.add("smolVLMPort must be positive, got " + smolVLMPort);
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
