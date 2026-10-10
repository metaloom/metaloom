package io.metaloom.cortex.node.watermark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cancelled task must not leave its ffmpeg behind.
 *
 * <p>
 * {@code FfmpegRunner} waits for the child with a wall clock and kills it on timeout - but an interrupt arriving during that wait used to propagate
 * straight out, leaving the child running unsupervised for as long as the encode took. A stand-in "ffprobe" that just sleeps is enough to pin it: no
 * ffmpeg installation needed.
 * </p>
 */
@DisabledOnOs(OS.WINDOWS)
class FfmpegRunnerInterruptTest {

	@TempDir
	Path tempDir;

	@Test
	void testInterruptKillsTheChildProcess() throws Exception {
		Path sleeper = tempDir.resolve("fake-ffprobe.sh");
		Files.writeString(sleeper, "#!/bin/sh\nexec sleep 60\n");
		Files.setPosixFilePermissions(sleeper, PosixFilePermissions.fromString("rwx------"));

		FfmpegRunner runner = new FfmpegRunner("ffmpeg", sleeper.toString(), 120_000);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				runner.probe(tempDir.resolve("clip.mp4"));
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "ffprobe-interrupt-test");
		worker.start();

		List<ProcessHandle> children = waitForChildren(10_000);
		assertFalse(children.isEmpty(), "the stand-in ffprobe never started");

		worker.interrupt();
		worker.join(10_000);
		assertFalse(worker.isAlive(), "probe() did not return after the interrupt");
		assertInstanceOf(IOException.class, failure.get());

		for (ProcessHandle child : children) {
			child.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
			assertFalse(child.isAlive(), "the child process outlived the cancelled task");
		}
	}

	private static List<ProcessHandle> waitForChildren(long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			List<ProcessHandle> children = ProcessHandle.current().children().filter(ProcessHandle::isAlive).toList();
			if (!children.isEmpty()) {
				return children;
			}
			Thread.sleep(20);
		}
		return List.of();
	}
}
