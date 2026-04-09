package io.metaloom.cortex.pipeline.core.node.filter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;

/**
 * Filters media based on text outputs from upstream nodes checked against a blacklist.
 * The blacklist is a newline-delimited file of terms.
 *
 * <p>The filter reads specified output keys from upstream nodes (e.g. {@code "whisper:transcript"},
 * {@code "ocr:text"}) and checks each against the blacklist in the configured match mode.</p>
 *
 * <p>If <em>any</em> blacklisted term matches, the media is <strong>rejected</strong>.</p>
 */
public class BlacklistFilterNode extends AbstractFilterNode {

	private static final Logger log = LoggerFactory.getLogger(BlacklistFilterNode.class);

	/**
	 * Match mode for blacklist comparison.
	 */
	public enum MatchMode {
		/** Term must equal the text (or a whitespace-delimited token). */
		EXACT,
		/** Term must appear as a substring of the text. */
		CONTAINS,
		/** Term is interpreted as a regex pattern. */
		REGEX
	}

	private final Set<String> blacklistTerms;
	private final List<UpstreamOutputKey> upstreamKeys;
	private final MatchMode matchMode;
	private final boolean caseSensitive;
	private List<Pattern> compiledPatterns;

	private BlacklistFilterNode(Builder builder) {
		super(builder.id, builder.name, builder.dependencies);
		this.blacklistTerms = Collections.unmodifiableSet(builder.blacklistTerms);
		this.upstreamKeys = Collections.unmodifiableList(builder.upstreamKeys);
		this.matchMode = builder.matchMode;
		this.caseSensitive = builder.caseSensitive;
		if (matchMode == MatchMode.REGEX) {
			int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
			List<Pattern> patterns = new ArrayList<>(blacklistTerms.size());
			for (String term : blacklistTerms) {
				patterns.add(Pattern.compile(term, flags));
			}
			this.compiledPatterns = Collections.unmodifiableList(patterns);
		}
	}

	@Override
	protected boolean evaluate(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		for (UpstreamOutputKey key : upstreamKeys) {
			if (upstreamResults == null) {
				continue;
			}
			NodeResult nr = upstreamResults.get(key.nodeId());
			if (nr == null || nr.getOutput() == null) {
				continue;
			}
			Object val = nr.getOutput().get(key.outputKey());
			if (val == null) {
				continue;
			}
			String text = val.toString();
			if (isBlacklisted(text)) {
				return false; // rejected
			}
		}
		return true; // passed
	}

	private boolean isBlacklisted(String text) {
		switch (matchMode) {
			case EXACT:
				return isExactMatch(text);
			case CONTAINS:
				return isContainsMatch(text);
			case REGEX:
				return isRegexMatch(text);
			default:
				return false;
		}
	}

	private boolean isExactMatch(String text) {
		String[] tokens = text.split("\\s+");
		for (String token : tokens) {
			for (String term : blacklistTerms) {
				if (caseSensitive ? token.equals(term) : token.equalsIgnoreCase(term)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean isContainsMatch(String text) {
		String searchText = caseSensitive ? text : text.toLowerCase();
		for (String term : blacklistTerms) {
			String searchTerm = caseSensitive ? term : term.toLowerCase();
			if (searchText.contains(searchTerm)) {
				return true;
			}
		}
		return false;
	}

	private boolean isRegexMatch(String text) {
		for (Pattern pattern : compiledPatterns) {
			if (pattern.matcher(text).find()) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected String rejectReason(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return "blacklisted content detected";
	}

	/**
	 * Reference to a specific output key from an upstream node.
	 */
	public record UpstreamOutputKey(String nodeId, String outputKey) {

		/**
		 * Parse a colon-delimited string like {@code "whisper:transcript"}.
		 */
		public static UpstreamOutputKey parse(String spec) {
			int colon = spec.indexOf(':');
			if (colon < 0) {
				throw new IllegalArgumentException("Expected 'nodeId:outputKey' but got: " + spec);
			}
			return new UpstreamOutputKey(spec.substring(0, colon), spec.substring(colon + 1));
		}
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
		private Set<String> dependencies = Set.of();
		private Set<String> blacklistTerms = new HashSet<>();
		private List<UpstreamOutputKey> upstreamKeys = new ArrayList<>();
		private MatchMode matchMode = MatchMode.CONTAINS;
		private boolean caseSensitive = false;

		private Builder(String id) {
			this.id = id;
			this.name = id;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder dependencies(Set<String> dependencies) {
			this.dependencies = dependencies;
			return this;
		}

		/** Add a single blacklist term. */
		public Builder blacklistTerm(String term) {
			this.blacklistTerms.add(term);
			return this;
		}

		/** Add multiple blacklist terms. */
		public Builder blacklistTerms(Set<String> terms) {
			this.blacklistTerms.addAll(terms);
			return this;
		}

		/**
		 * Load blacklist terms from a newline-delimited file. Blank lines and
		 * lines starting with {@code #} are ignored.
		 */
		public Builder blacklistFile(Path path) throws IOException {
			try (BufferedReader reader = Files.newBufferedReader(path)) {
				String line;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (!line.isEmpty() && !line.startsWith("#")) {
						blacklistTerms.add(line);
					}
				}
			}
			return this;
		}

		/**
		 * Specify an upstream output to check. Format: {@code "nodeId:outputKey"}.
		 */
		public Builder checkOutput(String spec) {
			this.upstreamKeys.add(UpstreamOutputKey.parse(spec));
			return this;
		}

		/** Specify an upstream output to check. */
		public Builder checkOutput(String nodeId, String outputKey) {
			this.upstreamKeys.add(new UpstreamOutputKey(nodeId, outputKey));
			return this;
		}

		public Builder matchMode(MatchMode matchMode) {
			this.matchMode = matchMode;
			return this;
		}

		public Builder caseSensitive(boolean caseSensitive) {
			this.caseSensitive = caseSensitive;
			return this;
		}

		public BlacklistFilterNode build() {
			if (upstreamKeys.isEmpty()) {
				throw new IllegalStateException("At least one upstream output key must be configured");
			}
			return new BlacklistFilterNode(this);
		}
	}
}
