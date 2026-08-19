package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video4j.Video4j;

/**
 * {@link FingerprintNode}'s failure path reports FAILED and keeps the cause.
 *
 * <p>
 * It ended in {@code ctx.failure(cause).next()} until 2026-08-18 — and because the node also emits
 * the literal {@code "NULL"} on its fingerprint port before returning, a downstream dedup node saw a
 * SUCCESS carrying a value. A dedup proposal that never appeared because the fingerprint was never
 * computed looked exactly like a corpus with no duplicates in it.
 * </p>
 */
class FingerprintNodeFailureTest {

	static {
		// The fingerprint is computed by opening the file through video4j.
		Video4j.init();
	}

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	@Test
	void testUndecodableVideoIsFailedEvenThoughThePortCarriesAValue() {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "this is not a video at all");
		StubLoomMedia media = new StubLoomMedia(backing.file().getAbsolutePath(), true, false, false, false);
		media.setSHA512(HASH);

		FingerprintNode node = new FingerprintNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new FingerprintNodeOptions());

		assertThat(node.process(NodeContext.create(media)))
			.isFailed()
			// The "NULL" placeholder is deliberate - the port stays wired so a downstream node is not
			// starved - which is precisely why the terminal state has to carry the truth instead.
			.hasOutput(FingerprintNode.OUT_FINGERPRINT, "NULL");
	}
}
