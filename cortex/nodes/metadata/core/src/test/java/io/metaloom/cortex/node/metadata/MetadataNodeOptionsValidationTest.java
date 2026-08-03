package io.metaloom.cortex.node.metadata;

import static io.metaloom.cortex.node.metadata.assertj.MetadataNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

class MetadataNodeOptionsValidationTest {

	@Test
	void testDefaultOptionsValid() {
		assertThat(new MetadataNodeOptions()).isValid();
	}

	@Test
	void testNegativeTimeoutInvalid() {
		MetadataNodeOptions options = new MetadataNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	void testNonPositiveRawCapsInvalid() {
		assertThat(new MetadataNodeOptions().setRawMaxKeys(0))
			.isInvalid().hasError("rawMaxKeys must be positive");
		assertThat(new MetadataNodeOptions().setRawMaxValueBytes(-1))
			.isInvalid().hasError("rawMaxValueBytes must be positive");
	}

	@Test
	void testNonPositiveTrackSampleCapInvalid() {
		assertThat(new MetadataNodeOptions().setGpsTrackMaxSamples(0))
			.isInvalid().hasError("gpsTrackMaxSamples must be positive");
	}

	@Test
	void testGpsRoundDecimalsOutOfRangeInvalid() {
		assertThat(new MetadataNodeOptions().setGpsRoundDecimals(-1))
			.isInvalid().hasError("gpsRoundDecimals must be between 0 and 6");
		assertThat(new MetadataNodeOptions().setGpsRoundDecimals(7))
			.isInvalid().hasError("gpsRoundDecimals must be between 0 and 6");
		assertThat(new MetadataNodeOptions().setGpsRoundDecimals(0)).isValid();
		assertThat(new MetadataNodeOptions().setGpsRoundDecimals(6)).isValid();
	}

	@Test
	void testNullPolicyAndFallbackInvalid() {
		assertThat(new MetadataNodeOptions().setGpsPolicy(null))
			.isInvalid().hasError("gpsPolicy must not be null");
		assertThat(new MetadataNodeOptions().setDateFallback(null))
			.isInvalid().hasError("dateFallback must not be null");
	}

	@Test
	void testValidationResultDirect() {
		ValidationResult result = new MetadataNodeOptions().validate();
		assertThat(result).isValid().hasNoErrors();
	}

	@Test
	void testDigestChangesWithEveryOptionThatChangesTheOutput() {
		// The digest is half the cache key. An option that changes the envelope but not the digest
		// would let one configured instance serve another's answer.
		String base = new MetadataNodeOptions().digest();

		assertThat(new MetadataNodeOptions().setIncludeRaw(true).digest()).isNotEqualTo(base);
		assertThat(new MetadataNodeOptions().setGpsPolicy(GpsPolicy.DROP).digest()).isNotEqualTo(base);
		assertThat(new MetadataNodeOptions().setGpsRoundDecimals(4).digest()).isNotEqualTo(base);
		assertThat(new MetadataNodeOptions().setDateFallback(DateFallback.FILESYSTEM).digest()).isNotEqualTo(base);
		assertThat(new MetadataNodeOptions().setEmitText(false).digest()).isNotEqualTo(base);
		assertThat(new MetadataNodeOptions().setLicenseDetection(false).digest()).isNotEqualTo(base);
		assertThat(new MetadataNodeOptions().setReadXmpSidecar(false).digest()).isNotEqualTo(base);
		assertThat(new MetadataNodeOptions().setExcludeKeys(java.util.List.of("exif:Make")).digest())
			.isNotEqualTo(base);
	}
}
