package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchProviderInfo;
import io.vertx.core.json.JsonObject;

/**
 * Shared handling for the two tools that query the {@code SearchProvider} SPI.
 *
 * <p>
 * Both of them answer a model, not a UI, so a broken or unconfigured search backend must come back as a sentence the model can act on. The failure
 * modes that matter are the two below: the provider is bound but cannot serve ({@code NoopSearchProvider}), or the query itself was rejected (a blank
 * or oversized term, an unsupported mode, an offset past the deep-paging cap). Neither may surface as an empty success — "no results" and "search is
 * off" are indistinguishable to a caller, and the model would report a confident, wrong "nothing found".
 * </p>
 */
final class SearchToolSupport {

	private SearchToolSupport() {
	}

	/**
	 * Availability guard.
	 *
	 * @return {@code null} when the provider can serve queries, otherwise the tool result to answer with. {@code NoopSearchProvider.search()} throws a
	 *         503 {@code LoomRestException}, so calling it anyway would hand the model a stack trace.
	 */
	static JsonObject unavailable(SearchProvider provider) {
		if (provider.isAvailable()) {
			return null;
		}
		SearchProviderInfo info = provider.info();
		String reason = info == null || info.getReason() == null ? "no reason reported" : info.getReason();
		return mcpTextResult("Search is unavailable (provider '" + provider.name() + "'): " + reason
			+ "\nNothing could be looked up. This is a server configuration issue, not an empty catalog — do not report it as 'no results'.");
	}

	/**
	 * Turn a provider rejection into an answer.
	 *
	 * <p>
	 * Catching {@code RuntimeException} rather than {@code LoomRestException} is deliberate: two classes of that name exist in the same package in
	 * {@code loom-shared/api} and {@code loom/common}, and which one reaches this module depends on classpath order. Both are {@code RuntimeException}s
	 * carrying the message, which is the only part used here.
	 * </p>
	 */
	static JsonObject failed(RuntimeException e) {
		String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		return mcpTextResult("The search could not be run: " + message);
	}

	/**
	 * Normalize a MIME type filter to what the index matches.
	 *
	 * <p>
	 * The provider filters {@code mime_type LIKE '<value>%'}, so a wildcard is not merely unnecessary, it matches nothing: a model that passes the
	 * conventional {@code video/*} would otherwise get a confident empty result. Trailing {@code *} is dropped; everything else is passed through.
	 * </p>
	 */
	static String mimeTypePrefix(String mimeType) {
		if (mimeType == null || mimeType.isBlank()) {
			return null;
		}
		String prefix = mimeType.trim();
		while (prefix.endsWith("*")) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		return prefix.isEmpty() ? null : prefix;
	}

	/**
	 * Strip the {@code <b>} markers {@code ts_headline} wraps matches in.
	 *
	 * <p>
	 * A highlight is <b>not</b> sanitised HTML — Postgres returns the source document otherwise verbatim, and that document is built from filenames,
	 * tag names and transcripts, all user supplied. A snippet leaves this process as text in a chat answer, so the markup is removed rather than
	 * relayed; the surrounding words already show what matched.
	 * </p>
	 */
	static String plainSnippet(String highlight) {
		if (highlight == null) {
			return null;
		}
		String snippet = highlight.replace("<b>", "").replace("</b>", "").trim();
		return snippet.isEmpty() ? null : snippet;
	}
}
