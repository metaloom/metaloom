package io.metaloom.loom.rest.model.media;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Where a remuxed stream asked to begin at a given offset will <em>actually</em> begin.
 *
 * <p>
 * The stream route copies the video rather than re-encoding it, so it can only start on a keyframe: asking for minute ten of a file whose
 * keyframes are five seconds apart yields a response whose first picture is up to five seconds earlier than that. The bytes are correct; what is
 * missing is any way for the player to know it.
 * </p>
 *
 * <p>
 * Without this the player has to assume the response starts where it asked, and that assumption is wrong by the distance back to the preceding
 * keyframe. Everything downstream of the player's clock then inherits the error: the transcript highlights a line the viewer has not reached yet,
 * a detection box appears before the face does, and clicking a phrase to hear it plays something else. The error is invisible in a short clip and
 * obvious in a 43-minute episode, which is why it survived as long as it did.
 * </p>
 *
 * <p>
 * Answered by performing the same seek the stream will perform and reading the first frame out of it, rather than by predicting it from the
 * container index: ffmpeg applies a seek margin that the index does not know about, so a prediction is wrong for a band of offsets just after
 * each keyframe. The request that follows must still carry the offset the viewer asked for - sending this value back snaps a second time.
 * </p>
 */
public class StreamStartResponse implements RestResponseModel<StreamStartResponse> {

	@JsonPropertyDescription("The offset in seconds that was asked for.")
	private Double requested;

	// One line, not a concatenation: the Python model generator is a line scanner and drops a description split across a "+".
	@JsonPropertyDescription("The offset a stream requested at that point will really begin at: the keyframe at or before it. Treat it as the origin of the player's clock. Do NOT send it back as the stream's 't' - keep asking for the offset you wanted, or the seek snaps a second time and lands a further group of pictures back.")
	private Double start;

	public Double getRequested() {
		return requested;
	}

	public StreamStartResponse setRequested(Double requested) {
		this.requested = requested;
		return this;
	}

	public Double getStart() {
		return start;
	}

	public StreamStartResponse setStart(Double start) {
		this.start = start;
		return this;
	}

	@Override
	public StreamStartResponse self() {
		return this;
	}
}
