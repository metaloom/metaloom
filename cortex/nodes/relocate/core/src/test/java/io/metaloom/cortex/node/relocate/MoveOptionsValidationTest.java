package io.metaloom.cortex.node.relocate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

class MoveOptionsValidationTest {

	private MoveNodeOptions options() {
		return new MoveNodeOptions();
	}

	private void assertValid(MoveNodeOptions options) {
		ValidationResult result = options.validate();
		assertTrue(result.isValid(), "Expected valid options but got: " + result.getErrors());
	}

	private void assertError(MoveNodeOptions options, String fragment) {
		ValidationResult result = options.validate();
		assertFalse(result.isValid(), "Expected invalid options");
		assertTrue(result.getErrors().stream().anyMatch(e -> e.contains(fragment)),
			"Expected an error containing '" + fragment + "' but got: " + result.getErrors());
	}

	@Test
	void testTheDefaultsAreValid() {
		assertValid(options());
	}

	@Test
	void testABlankTargetFolderIsRejected() {
		assertError(options().setTargetFolder(Paths.get("")), "targetFolder");
	}

	/**
	 * An unknown enum value names the accepted ones. A pipeline author who typed {@code OVERWRITE} should be told what to type instead - especially
	 * here, where the value they wanted deliberately does not exist.
	 */
	@Test
	void testAnUnknownConflictPolicyNamesTheAcceptedValues() {
		ValidationResult result = options().setOnConflict("OVERWRITE").validate();
		assertFalse(result.isValid());
		assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("SUFFIX") && e.contains("SKIP") && e.contains("FAIL")),
			"Expected the accepted values in the message, got: " + result.getErrors());
	}

	@Test
	void testAnUnknownCrossDevicePolicyIsRejected() {
		assertError(options().setCrossDevice("MAYBE"), "crossDevice");
	}

	@Test
	void testAnUnknownSourcePolicyIsRejected() {
		assertError(options().setSourcePolicy("DELETE"), "sourcePolicy");
	}

	@Test
	void testAnUnknownVerifyPolicyIsRejected() {
		assertError(options().setVerify("MD5"), "verify");
	}

	/**
	 * 🔴 The combination that must be unreachable: an S3 object cannot be digested without downloading it back, so asking to delete the original on the
	 * strength of a SHA-512 comparison would either delete on an unproven copy or silently re-download everything.
	 */
	@Test
	void testDeletingAfterASha512VerifyIsRejectedForABucketTarget() {
		MoveNodeOptions options = options()
			.setTarget(MoveTarget.S3_BUCKET)
			.setBucket("archive")
			.setSourcePolicy("DELETE_AFTER_VERIFY")
			.setVerify("SHA512");
		assertError(options, "SHA512");
	}

	@Test
	void testDeletingAfterASizeVerifyIsAllowedForABucketTarget() {
		assertValid(options()
			.setTarget(MoveTarget.S3_BUCKET)
			.setBucket("archive")
			.setSourcePolicy("DELETE_AFTER_VERIFY")
			.setVerify("SIZE"));
	}

	// --- per-target requirements, which live on the destinations because configure() enforces them ---

	@Test
	void testTheBucketTargetRequiresABucket() {
		java.util.List<String> problems = new S3BucketDestination(io.metaloom.cortex.s3.S3Support.inactive())
			.validate(options().setTarget(MoveTarget.S3_BUCKET));
		assertTrue(problems.stream().anyMatch(p -> p.contains("bucket")), "Expected a bucket requirement, got: " + problems);
	}

	@Test
	void testThePoolTargetRequiresAPoolUuid() {
		java.util.List<String> problems = new PoolDestination().validate(options().setTarget(MoveTarget.POOL));
		assertTrue(problems.stream().anyMatch(p -> p.contains("poolUuid")), "Expected a poolUuid requirement, got: " + problems);
	}

	@Test
	void testAMalformedPoolUuidIsRejected() {
		java.util.List<String> problems = new PoolDestination().validate(options().setTarget(MoveTarget.POOL).setPoolUuid("not-a-uuid"));
		assertTrue(problems.stream().anyMatch(p -> p.contains("not a valid uuid")), "Expected a uuid complaint, got: " + problems);
	}

	@Test
	void testTheLibraryTargetRequiresALibraryUuid() {
		java.util.List<String> problems = new LibraryDestination(new PoolDestination()).validate(options().setTarget(MoveTarget.LIBRARY));
		assertTrue(problems.stream().anyMatch(p -> p.contains("libraryUuid")), "Expected a libraryUuid requirement, got: " + problems);
	}
}
