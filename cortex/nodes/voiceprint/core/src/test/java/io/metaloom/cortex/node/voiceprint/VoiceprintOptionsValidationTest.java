package io.metaloom.cortex.node.voiceprint;

import static io.metaloom.cortex.node.voiceprint.assertj.VoiceprintNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

public class VoiceprintOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		assertThat(new VoiceprintNodeOptions()).isValid()
			.hasHost("localhost")
			.hasPort(9150)
			.hasModel("")
			.hasWindow(3.0, 1.5)
			.persistsWindows(true);
	}

	@Test
	public void testCustomOptionsValid() {
		VoiceprintNodeOptions options = new VoiceprintNodeOptions().setVoiceprintHost("voiceprint.internal").setVoiceprintPort(9300)
			.setModel("wespeaker").setWindowSeconds(2.0).setHopSeconds(2.0).setClusterThreshold(-0.5).setMaxSpeakers(3).setPersistWindows(false);
		assertThat(options).isValid().hasHost("voiceprint.internal").hasPort(9300).hasModel("wespeaker").hasWindow(2.0, 2.0).persistsWindows(false);
	}

	@Test
	public void testEmptyHostInvalid() {
		assertThat(new VoiceprintNodeOptions().setVoiceprintHost(" ")).isInvalid().hasError("voiceprintHost must not be empty");
	}

	@Test
	public void testNonPositivePortInvalid() {
		assertThat(new VoiceprintNodeOptions().setVoiceprintPort(0)).isInvalid().hasError("voiceprintPort must be positive, got 0");
	}

	@Test
	public void testWindowInvalid() {
		assertThat(new VoiceprintNodeOptions().setWindowSeconds(0)).isInvalid().hasError("windowSeconds must be positive, got 0.0");
	}

	@Test
	public void testHopLongerThanWindowInvalid() {
		assertThat(new VoiceprintNodeOptions().setHopSeconds(4.0)).isInvalid()
			.hasError("hopSeconds must not exceed windowSeconds, got 4.0 > 3.0");
	}

	@Test
	public void testMinSpeechInvalid() {
		assertThat(new VoiceprintNodeOptions().setMinSpeechSeconds(-1)).isInvalid().hasError("minSpeechSeconds must be positive, got -1.0");
	}

	@Test
	public void testThresholdOutOfRangeInvalid() {
		assertThat(new VoiceprintNodeOptions().setClusterThreshold(1.5)).isInvalid()
			.hasError("clusterThreshold must be a cosine similarity in [-1, 1], got 1.5");
	}

	@Test
	public void testNegativeMaxSpeakersInvalid() {
		assertThat(new VoiceprintNodeOptions().setMaxSpeakers(-1)).isInvalid().hasError("maxSpeakers must be non-negative, got -1");
	}

	@Test
	public void testEmptyFfmpegInvalid() {
		assertThat(new VoiceprintNodeOptions().setFfmpegPath("")).isInvalid().hasError("ffmpegPath must not be empty");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		VoiceprintNodeOptions options = new VoiceprintNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative, got -1");
	}
}
