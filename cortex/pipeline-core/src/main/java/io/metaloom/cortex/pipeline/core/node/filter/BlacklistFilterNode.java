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
import io.metaloom.cortex.api.node.Element;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

/**
 * Filters media based on text outputs from upstream nodes checked against a blacklist.
 * The blacklist is a newline-delimited file of terms.
 *
 * <p>The texts arrive on the declared {@link #IN_TEXT} port and each is checked against the
 * blacklist in the configured match mode. The port is {@code MANY} because checking a
 * transcript <em>and</em> an OCR pass is the normal case - the pipeline author wires both
 * producers into it, instead of listing {@code "whisper:transcript"} and {@code "ocr:text"} as
 * node-id strings that broke the moment either node was renamed.</p>
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

	/** Every text to check. Several producers may feed it; their elements concatenate. */
	public static final InputPort<String> IN_TEXT = InputPort.many("text", ContentTypeRegistry.TEXT_ANY, String.class);

	private final Set<String> blacklistTerms;
	private final MatchMode matchMode;
	private final boolean caseSensitive;
	private List<Pattern> compiledPatterns;

	private BlacklistFilterNode(Builder builder) {
		super(builder.id, builder.name);
		this.blacklistTerms = Collections.unmodifiableSet(builder.blacklistTerms);
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
	protected boolean evaluate(NodeContext<LoomMedia> ctx) {
		for (Element<String> element : ctx.inputs(IN_TEXT)) {
			if (element.value() != null && isBlacklisted(element.value())) {
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
	protected String rejectReason(NodeContext<LoomMedia> ctx) {
		return "blacklisted content detected";
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
		private Set<String> blacklistTerms = new HashSet<>();
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

		public Builder matchMode(MatchMode matchMode) {
			this.matchMode = matchMode;
			return this;
		}

		public Builder caseSensitive(boolean caseSensitive) {
			this.caseSensitive = caseSensitive;
			return this;
		}

		public BlacklistFilterNode build() {
			return new BlacklistFilterNode(this);
		}
	}
}
