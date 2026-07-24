package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.node.filter.BlacklistFilterNode.MatchMode;
import io.metaloom.cortex.pipeline.core.node.filter.BlacklistFilterNode.UpstreamOutputKey;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

class BlacklistFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/doc.pdf", false, false, false, true);

	@TempDir
	File tempDir;

	private boolean passed(BlacklistFilterNode filter, Object text) {
		Map<String, NodeResult> upstream = text == null
				? upstream("tika", Map.of())
				: upstream("tika", Map.of("content", text));
		return evaluate(filter, MEDIA, upstream).<Boolean> getOutput(PipelineNode.FILTER_PASSED);
	}

	@Test
	void testBuildRequiresAtLeastOneUpstreamOutput() {
		assertThatThrownBy(() -> BlacklistFilterNode.builder("bl").blacklistTerm("spam").build())
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("At least one upstream output key must be configured");
	}

	@Test
	void testUpstreamOutputKeySpecSplitsOnTheFirstColon() {
		assertThat(UpstreamOutputKey.parse("tika:content"))
				.isEqualTo(new UpstreamOutputKey("tika", "content"));
		assertThat(UpstreamOutputKey.parse("llm:result:text"))
				.as("only the first colon separates node id from output key")
				.isEqualTo(new UpstreamOutputKey("llm", "result:text"));
	}

	@Test
	void testUpstreamOutputKeySpecWithoutColonIsRejected() {
		assertThatThrownBy(() -> BlacklistFilterNode.builder("bl").checkOutput("tika"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Expected 'nodeId:outputKey' but got: tika");
	}

	@Test
	void testContainsIsTheDefaultMatchModeAndIsCaseInsensitive() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
				.blacklistTerm("spam")
				.build();

		assertThat(passed(filter, "this is SPAMmy content")).as("substring, different case").isFalse();
		assertThat(passed(filter, "perfectly fine content")).isTrue();
	}

	@Test
	void testCaseSensitiveContains() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
				.blacklistTerm("Spam")
				.caseSensitive(true)
				.build();

		assertThat(passed(filter, "contains Spam here")).isFalse();
		assertThat(passed(filter, "contains spam here")).as("case must match").isTrue();
	}

	@Test
	void testExactMatchesWholeTokensOnly() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
				.blacklistTerm("spam")
				.matchMode(MatchMode.EXACT)
				.build();

		assertThat(passed(filter, "some spam here")).as("whole token").isFalse();
		assertThat(passed(filter, "some spammy here")).as("substring is not a token match").isTrue();
	}

	@Test
	void testRegexMatchesAnywhereInTheText() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
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
				.checkOutput("tika:content")
				.blacklistFile(listFile)
				.build();

		assertThat(passed(filter, "spam")).as("terms are trimmed").isFalse();
		assertThat(passed(filter, "phishing")).isFalse();
		assertThat(passed(filter, "# a comment")).as("comment lines are not terms").isTrue();
	}

	@Test
	void testMultipleUpstreamKeysAreAllChecked() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika", "content")
				.checkOutput("ocr", "text")
				.blacklistTerm("spam")
				.build();

		Map<String, NodeResult> clean = Map.of(
				"tika", NodeResult.success("tika", 0, Map.of("content", "fine")),
				"ocr", NodeResult.success("ocr", 0, Map.of("text", "also fine")));
		Map<String, NodeResult> dirtyOcr = Map.of(
				"tika", NodeResult.success("tika", 0, Map.of("content", "fine")),
				"ocr", NodeResult.success("ocr", 0, Map.of("text", "spam")));

		assertThat(evaluate(filter, MEDIA, clean).<Boolean> getOutput(PipelineNode.FILTER_PASSED)).isTrue();
		assertThat(evaluate(filter, MEDIA, dirtyOcr).<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("a hit on any configured key rejects")
				.isFalse();
	}

	@Test
	void testMissingUpstreamDataPasses() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
				.blacklistTerm("spam")
				.build();

		assertThat(evaluate(filter, MEDIA).<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("no upstream results at all")
				.isTrue();
		assertThat(evaluate(filter, MEDIA, upstream("other-node", Map.of("content", "spam")))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("upstream node id does not match")
				.isTrue();
		assertThat(passed(filter, null)).as("output key absent").isTrue();
	}

	/**
	 * Values are coerced with {@code toString()}, so a list of extracted terms is
	 * matched in its bracketed form rather than element by element.
	 */
	@Test
	void testNonStringValuesAreStringified() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
				.blacklistTerm("spam")
				.build();

		assertThat(passed(filter, List.of("spam", "eggs"))).isFalse();
		assertThat(passed(filter, Set.of("eggs"))).isTrue();
	}

	@Test
	void testNoTermsConfiguredPassesEverything() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
				.build();

		assertThat(passed(filter, "spam phishing malware")).isTrue();
	}

	@Test
	void testRejectReason() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
				.blacklistTerm("spam")
				.build();
		NodeResult result = evaluate(filter, MEDIA, upstream("tika", Map.of("content", "spam")));

		assertThat(result.<String> getOutput("filter_reason")).isEqualTo("blacklisted content detected");
	}

	@Test
	void testCleanContentRoutesToPassBranch() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
				.blacklistTerm("spam")
				.build();
		FixedOutputNode tika = new FixedOutputNode("tika", Map.of("content", "a wholesome document"));

		PipelineResult result = route(MEDIA, filter, tika);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("bl", PipelineNode.FILTER_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testBlacklistedContentRoutesToRejectBranch() {
		BlacklistFilterNode filter = BlacklistFilterNode.builder("bl")
				.checkOutput("tika:content")
				.blacklistTerm("spam")
				.build();
		FixedOutputNode tika = new FixedOutputNode("tika", Map.of("content", "buy our spam today"));

		PipelineResult result = route(MEDIA, filter, tika);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("bl", PipelineNode.FILTER_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
