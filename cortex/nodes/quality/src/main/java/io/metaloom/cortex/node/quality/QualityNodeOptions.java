package io.metaloom.cortex.node.quality;

import javax.inject.Inject;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class QualityNodeOptions extends AbstractNodeOptions<QualityNodeOptions> {

	public static final String KEY = "quality";

	private boolean checkBlurriness = true;

	private boolean checkResolution = true;

	private boolean checkVideoBitrate = true;

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
}
