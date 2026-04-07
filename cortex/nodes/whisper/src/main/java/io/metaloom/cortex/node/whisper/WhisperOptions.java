package io.metaloom.cortex.node.whisper;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class WhisperOptions extends AbstractNodeOptions<WhisperOptions> {

	public static final String KEY = "whisper";

	private String modelPath = "models/ggml-large-v3-turbo.bin";

	private float temperature = 0.0f;

	private float temperatureInc = 0.2f;

	private String language;

	private boolean useGpu = true;

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
}
