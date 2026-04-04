package io.metaloom.cortex.pipeline.api.filter;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Filter that determines whether a media item should be processed by a pipeline.
 */
public interface PipelineFilter {

	/**
	 * Test whether the given media item matches this filter.
	 *
	 * @param media the media item to test
	 * @return true if the media should be processed
	 */
	boolean matches(LoomMedia media);

	/**
	 * Human-readable description of this filter.
	 */
	String describe();
}
