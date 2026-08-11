package io.metaloom.loom.rest.model.share;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractResponse;

/**
 * Everything a share visitor said, in one response.
 *
 * <p>
 * All three collections together rather than three paged routes: a review is read as a whole, the volume is bounded by what one person typed into one
 * link, and a comment anchored to an annotation is unreadable without the annotation. Three requests that then have to be joined in the browser would
 * be more code on both sides to render the same panel.
 * </p>
 */
public class ShareFeedbackResponse extends AbstractResponse<ShareFeedbackResponse> {

	@JsonPropertyDescription("The name the visitor gave when they first opened the link.")
	private String visitorName;

	private List<ShareCommentResponse> comments = new ArrayList<>();

	private List<ShareAnnotationResponse> annotations = new ArrayList<>();

	private List<ShareReactionResponse> reactions = new ArrayList<>();

	public String getVisitorName() {
		return visitorName;
	}

	public ShareFeedbackResponse setVisitorName(String visitorName) {
		this.visitorName = visitorName;
		return this;
	}

	public List<ShareCommentResponse> getComments() {
		return comments;
	}

	public ShareFeedbackResponse setComments(List<ShareCommentResponse> comments) {
		this.comments = comments;
		return this;
	}

	public List<ShareAnnotationResponse> getAnnotations() {
		return annotations;
	}

	public ShareFeedbackResponse setAnnotations(List<ShareAnnotationResponse> annotations) {
		this.annotations = annotations;
		return this;
	}

	public List<ShareReactionResponse> getReactions() {
		return reactions;
	}

	public ShareFeedbackResponse setReactions(List<ShareReactionResponse> reactions) {
		this.reactions = reactions;
		return this;
	}

	@Override
	public ShareFeedbackResponse self() {
		return this;
	}
}
