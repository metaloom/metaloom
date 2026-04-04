package io.metaloom.cortex.pipeline.api.filter;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Set;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Composite filter that checks MIME type patterns and path globs.
 */
public class MediaFilter implements PipelineFilter {

	private final Set<String> mimePatterns;
	private final List<String> pathGlobs;

	public MediaFilter(Set<String> mimePatterns, List<String> pathGlobs) {
		this.mimePatterns = mimePatterns;
		this.pathGlobs = pathGlobs;
	}

	@Override
	public boolean matches(LoomMedia media) {
		boolean mimeMatch = matchesMime(media);
		boolean pathMatch = matchesPath(media);
		return mimeMatch && pathMatch;
	}

	private boolean matchesMime(LoomMedia media) {
		if (mimePatterns == null || mimePatterns.isEmpty()) {
			return true;
		}
		for (String pattern : mimePatterns) {
			if (pattern.equals("*/*")) {
				return true;
			}
			if (pattern.endsWith("/*")) {
				String type = pattern.substring(0, pattern.indexOf('/'));
				if ("video".equals(type) && media.isVideo()) return true;
				if ("image".equals(type) && media.isImage()) return true;
				if ("audio".equals(type) && media.isAudio()) return true;
				if ("document".equals(type) && media.isDocument()) return true;
			}
		}
		return false;
	}

	private boolean matchesPath(LoomMedia media) {
		if (pathGlobs == null || pathGlobs.isEmpty()) {
			return true;
		}
		String path = media.absolutePath();
		for (String glob : pathGlobs) {
			PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
			if (matcher.matches(media.path())) {
				return true;
			}
			// Also match against path string directly for simple patterns
			if (path.contains(glob.replace("**", "").replace("*", ""))) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String describe() {
		return "MediaFilter{mime=" + mimePatterns + ", paths=" + pathGlobs + "}";
	}

	public Set<String> getMimePatterns() {
		return mimePatterns;
	}

	public List<String> getPathGlobs() {
		return pathGlobs;
	}
}
