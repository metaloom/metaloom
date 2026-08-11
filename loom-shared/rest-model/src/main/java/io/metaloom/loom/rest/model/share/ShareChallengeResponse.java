package io.metaloom.loom.rest.model.share;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractResponse;

/**
 * What an unauthenticated visitor is told before they have proved anything: only what the front door needs to render itself.
 *
 * <p>
 * Deliberately austere. It answers "does this want a password" and "am I about to see one file or a set of them", and nothing else - no filename, no
 * collection name, no owner, no counts. A slug that has been guessed, leaked or found in a browser history must not become a way to learn what an
 * installation holds, and an expired or unknown slug never gets this far: both answer 404, so a lapsed link cannot be told apart from one that never
 * existed.
 * </p>
 */
public class ShareChallengeResponse extends AbstractResponse<ShareChallengeResponse> {

	@JsonPropertyDescription("What is behind the link: ASSET or COLLECTION. Enough for the viewer to pick a layout before it can load anything.")
	private String targetType;

	@JsonPropertyDescription("Whether a password must be supplied to open the link.")
	private Boolean passwordRequired;

	@JsonPropertyDescription("Whether somebody has already named this link. When true the viewer skips the name question and reuses the stored name.")
	private Boolean visitorNameKnown;

	@JsonPropertyDescription("The stored visitor name, when one exists. Shown back as a greeting rather than asked for again.")
	private String visitorName;

	public String getTargetType() {
		return targetType;
	}

	public ShareChallengeResponse setTargetType(String targetType) {
		this.targetType = targetType;
		return this;
	}

	public Boolean getPasswordRequired() {
		return passwordRequired;
	}

	public ShareChallengeResponse setPasswordRequired(Boolean passwordRequired) {
		this.passwordRequired = passwordRequired;
		return this;
	}

	public Boolean getVisitorNameKnown() {
		return visitorNameKnown;
	}

	public ShareChallengeResponse setVisitorNameKnown(Boolean visitorNameKnown) {
		this.visitorNameKnown = visitorNameKnown;
		return this;
	}

	public String getVisitorName() {
		return visitorName;
	}

	public ShareChallengeResponse setVisitorName(String visitorName) {
		this.visitorName = visitorName;
		return this;
	}

	@Override
	public ShareChallengeResponse self() {
		return this;
	}
}
