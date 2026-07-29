package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.core.node.filter.BlacklistFilterNode.MatchMode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

class BlacklistFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/doc.pdf", false, false, false, true);

	/**
	 * Stand-in for a text producer such as tika or ocr. Its port is {@code ONE} while
	 * the filter's is {@code MANY}: that is the normal shape, since the filter gathers
	 * whatever producers the pipeline author wired into it.
	 */
	private static final OutputPort<String> OUT_CONTENT =
		OutputPort.one(BlacklistFilterNode.IN_TEXT.id(), ContentTypeRegistry.TEXT_PLAIN, String.class);

	@TempDir
	File tempDir;

	private boolean passed(BlacklistFilterNode filter, String... texts) {
		return passed(evaluate(filter, MEDIA, NodeInputs.builder()
				.inputs(BlacklistFilterNode.IN_TEXT, List.of(texts))
				.build()));
	}

	private static FixedOutputNode tika(String content) {
		return new FixedOutputNode("tika", Map.of(OUT_CONTENT.id(), PortOutput.one(OUT_CONTENT, content)));
	}

	@Test
	void testContainsIsTheDefaultMatchModeAndIsCaseInsensitive() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.blacklistTerm("spam")
				.build();

		assertThat(passed(filter, "this is SPAMmy content")).as("substring, different case").isFalse();
		assertThat(passed(filter, "perfectly fine content")).isTrue();
	}

	@Test
	void testCaseSensitiveContains() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.blacklistTerm("Spam")
				.caseSensitive(true)
				.build();

		assertThat(passed(filter, "contains Spam here")).isFalse();
		assertThat(passed(filter, "contains spam here")).as("case must match").isTrue();
	}

	@Test
	void testExactMatchesWholeTokensOnly() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.blacklistTerm("spam")
				.matchMode(MatchMode.EXACT)
				.build();

		assertThat(passed(filter, "some spam here")).as("whole token").isFalse();
		assertThat(passed(filter, "some spammy here")).as("substring is not a token match").isTrue();
	}

	@Test
	void testRegexMatchesAnywhereInTheText() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.blacklistTerm("sp[a4]m")
				.matchMode(MatchMode.REGEX)
				.build();

		assertThat(passed(filter, "contains sp4m somewhere")).isFalse();
		assertThat(passed(filter, "contains spam somewhere")).isFalse();
		assertThat(passed(filter, "nothing objectionable")).isTrue();
	}

	@Test
	void testBlacklistFileSkipsBlankLinesAndComments() throws IOException {
		Path listFile = new File(tempDir, "terms.txt").toPath();
		Files.write(listFile, List.of(
				"# a comment",
				"",
				"  spam  ",
				"phishing"));

		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.blacklistFile(listFile)
				.build();

		assertThat(passed(filter, "spam")).as("terms are trimmed").isFalse();
		assertThat(passed(filter, "phishing")).isFalse();
		assertThat(passed(filter, "# a comment")).as("comment lines are not terms").isTrue();
	}

	/**
	 * The text port is {@code MANY}: several producers - a transcript and an OCR pass,
	 * say - concatenate into one element sequence, and a hit on any element rejects.
	 */
	@Test
	void testEveryTextElementIsChecked() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.blacklistTerm("spam")
				.build();

		assertThat(passed(filter, "fine", "also fine")).isTrue();
		assertThat(passed(filter, "fine", "spam"))
				.as("a hit on any element rejects")
				.isFalse();
	}

	@Test
	void testMissingTextPasses() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.blacklistTerm("spam")
				.build();

		assertThat(passed(evaluate(filter, MEDIA)))
				.as("nothing wired into the text port")
				.isTrue();
		assertThat(passed(filter))
				.as("wired, but the producers emitted no elements")
				.isTrue();
	}

	@Test
	void testNoTermsConfiguredPassesEverything() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl").build();

		assertThat(passed(filter, "spam phishing malware")).isTrue();
	}

	@Test
	void testCleanContentRoutesToPassBranch() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.blacklistTerm("spam")
				.build();

		PipelineResult result = route(MEDIA, filter, tika("a wholesome document"));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("bl", AbstractFilterNode.OUT_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testBlacklistedContentRoutesToRejectBranch() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.blacklistTerm("spam")
				.build();

		PipelineResult result = route(MEDIA, filter, tika("buy our spam today"));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("bl", AbstractFilterNode.OUT_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
