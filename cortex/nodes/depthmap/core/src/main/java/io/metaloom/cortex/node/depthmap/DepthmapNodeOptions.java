package io.metaloom.cortex.node.depthmap;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link DepthmapNode}.
 *
 * <p>
 * {@link #depthHost} / {@link #depthPort} address the FastAPI {@code /v1/depth} sidecar ({@code sidecars/depth}). {@link #mode} selects the model
 * family inside the sidecar: {@code RELATIVE} (the default) runs Depth Anything V2 Small, {@code METRIC} runs ZoeDepth and adds a metre range to the
 * result. {@link #model} overrides the sidecar's own checkpoint for the selected mode.
 * </p>
 *
 * <p>
 * {@link #maxDim} bounds inference cost by capping the longest side sent to the sidecar. It also determines the size of the produced map, which is why
 * the emitted metadata reports the map's dimensions separately from the source image's.
 * </p>
 */
public class DepthmapNodeOptions extends AbstractNodeOptions<DepthmapNodeOptions> {

	public static final String KEY = "depthmap";

	@ParamDoc(label = "Sidecar Host", description = "Host of the /v1/depth sidecar", order = 100)
	private String depthHost = "localhost";

	/** 9120 - 9100 is the TTS sidecar, 9110 sentiment, 9200 imagegen. */
	@ParamDoc(label = "Sidecar Port", description = "Port of the /v1/depth sidecar", min = "1", order = 110)
	private int depthPort = 9120;

	@ParamDoc(label = "Depth Mode",
		description = "RELATIVE orders objects front-to-back; METRIC additionally reports a range in metres", order = 115)
	private DepthMode mode = DepthMode.RELATIVE;

	/** Explicit checkpoint override, or null to use the sidecar's default for the mode. */
	@ParamDoc(label = "Model Override", description = "Checkpoint to use instead of the sidecar's default for the selected mode", order = 120)
	private String model;

	/** Longest side sent to the sidecar. The sidecar clamps this to its own DEPTH_MAX_DIM. */
	@ParamDoc(label = "Max Dimension", description = "Longest side sent to the sidecar; also the size of the produced map", min = "1", order = 130)
	private int maxDim = 1024;

	public DepthmapNodeOptions() {
		// timeoutMs is a common option on AbstractNodeOptions; depth inference on CPU is slow
		// enough that the framework default of 0 would be useless here.
		setTimeoutMs(120_000);
	}

	@Override
	protected DepthmapNodeOptions self() {
		return this;
	}

	public String getDepthHost() {
		return depthHost;
	}

	public DepthmapNodeOptions setDepthHost(String depthHost) {
		this.depthHost = depthHost;
		return this;
	}

	public int getDepthPort() {
		return depthPort;
	}

	public DepthmapNodeOptions setDepthPort(int depthPort) {
		this.depthPort = depthPort;
		return this;
	}

	public DepthMode getMode() {
		return mode;
	}

	public DepthmapNodeOptions setMode(DepthMode mode) {
		this.mode = mode;
		return this;
	}

	public String getModel() {
		return model;
	}

	public DepthmapNodeOptions setModel(String model) {
		this.model = model;
		return this;
	}

	public int getMaxDim() {
		return maxDim;
	}

	public DepthmapNodeOptions setMaxDim(int maxDim) {
		this.maxDim = maxDim;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (depthHost == null || depthHost.isBlank()) {
			errors.add("depthHost must not be empty");
		}
		if (depthPort <= 0) {
			errors.add("depthPort must be positive, got " + depthPort);
		}
		if (mode == null) {
			errors.add("mode must not be null");
		}
		if (maxDim <= 0) {
			errors.add("maxDim must be positive, got " + maxDim);
		}
		if (getTimeoutMs() <= 0) {
			errors.add("timeoutMs must be positive, got " + getTimeoutMs());
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
