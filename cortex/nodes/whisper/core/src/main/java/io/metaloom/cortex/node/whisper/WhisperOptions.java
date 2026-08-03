package io.metaloom.cortex.node.whisper;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class WhisperOptions extends AbstractNodeOptions<WhisperOptions> {

	public static final String KEY = "whisper";

	@ParamDoc(label = "Model Path", description = "Path to the Whisper model file")
	private String modelPath = "models/ggml-large-v3-turbo.bin";

	@ParamDoc(label = "Temperature", min = "0.0", max = "1.0", step = "0.1")
	private float temperature = 0.0f;

	@ParamDoc(label = "Temperature Increment", min = "0.0", max = "1.0", step = "0.05")
	private float temperatureInc = 0.2f;

	@ParamDoc(label = "Language", description = "Target language code (e.g. 'en', 'de')")
	private String language;

	@ParamDoc(label = "Use GPU")
	private boolean useGpu = true;

	@ParamDoc(label = "GPU Device Index", min = "0")
	private int gpuDevice = 0;

	@Override
	protected WhisperOptions self() {
		return this;
	}

	public String getModelPath() {
		return modelPath;
	}

	public void setModelPath(String modelPath) {
		this.modelPath = modelPath;
	}

	public float getTemperature() {
		return temperature;
	}

	public void setTemperature(float temperature) {
		this.temperature = temperature;
	}

	public float getTemperatureInc() {
		return temperatureInc;
	}

	public void setTemperatureInc(float temperatureInc) {
		this.temperatureInc = temperatureInc;
	}

	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		this.language = language;
	}

	public boolean isUseGpu() {
		return useGpu;
	}

	public void setUseGpu(boolean useGpu) {
		this.useGpu = useGpu;
	}

	public int getGpuDevice() {
		return gpuDevice;
	}

	public void setGpuDevice(int gpuDevice) {
		this.gpuDevice = gpuDevice;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		
		// Model path must not be empty
		if (modelPath == null || modelPath.isBlank()) {
			errors.add("modelPath must not be empty");
		}
		
		// Temperature must be non-negative
		if (temperature < 0) {
			errors.add("temperature must be non-negative, got " + temperature);
		}
		
		// Temperature increment must be non-negative
		if (temperatureInc < 0) {
			errors.add("temperatureInc must be non-negative, got " + temperatureInc);
		}
		
		// GPU device must be non-negative
		if (gpuDevice < 0) {
			errors.add("gpuDevice must be non-negative, got " + gpuDevice);
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
