package io.metaloom.cortex.node.quality;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class QualityNodeOptions extends AbstractNodeOptions<QualityNodeOptions> {

	public static final String KEY = "quality";

	@ParamDoc(label = "Check Blurriness")
	private boolean checkBlurriness = true;

	@ParamDoc(label = "Check Resolution")
	private boolean checkResolution = true;

	@ParamDoc(label = "Check Video Bitrate")
	private boolean checkVideoBitrate = true;

	@ParamDoc(label = "Check Audio Bitrate")
	private boolean checkAudioBitrate = true;

	@Inject
	public QualityNodeOptions() {
	}

	@Override
	protected QualityNodeOptions self() {
		return this;
	}

	public boolean isCheckBlurriness() {
		return checkBlurriness;
	}

	public QualityNodeOptions setCheckBlurriness(boolean checkBlurriness) {
		this.checkBlurriness = checkBlurriness;
		return this;
	}

	public boolean isCheckResolution() {
		return checkResolution;
	}

	public QualityNodeOptions setCheckResolution(boolean checkResolution) {
		this.checkResolution = checkResolution;
		return this;
	}

	public boolean isCheckVideoBitrate() {
		return checkVideoBitrate;
	}

	public QualityNodeOptions setCheckVideoBitrate(boolean checkVideoBitrate) {
		this.checkVideoBitrate = checkVideoBitrate;
		return this;
	}

	public boolean isCheckAudioBitrate() {
		return checkAudioBitrate;
	}

	public QualityNodeOptions setCheckAudioBitrate(boolean checkAudioBitrate) {
		this.checkAudioBitrate = checkAudioBitrate;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		
		// At least one quality check must be enabled
		if (!checkBlurriness && !checkResolution && !checkVideoBitrate && !checkAudioBitrate) {
			errors.add("At least one quality check must be enabled (checkBlurriness, checkResolution, checkVideoBitrate, or checkAudioBitrate)");
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
