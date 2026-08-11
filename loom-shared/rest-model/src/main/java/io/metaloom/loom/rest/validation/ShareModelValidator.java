package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.share.ShareAnnotationRequest;
import io.metaloom.loom.rest.model.share.ShareCommentRequest;
import io.metaloom.loom.rest.model.share.ShareCreateRequest;
import io.metaloom.loom.rest.model.share.ShareReactionRequest;
import io.metaloom.loom.rest.model.share.ShareResponse;
import io.metaloom.loom.rest.model.share.ShareSessionRequest;
import io.metaloom.loom.rest.model.share.ShareUpdateRequest;

public interface ShareModelValidator extends ModelValidator {

	/** The longest name a visitor may give themselves. Long enough for "Maria from the Acme brand team", short enough not to break a heading. */
	int MAX_VISITOR_NAME_LENGTH = 80;

	/** Comment and annotation text ceiling. Generous for a review note, bounded because this is an unauthenticated write path. */
	int MAX_TEXT_LENGTH = 8_000;

	default void validate(ShareCreateRequest request) {
		requireNonNullOrEmpty(request.getTargetType(), "A target type must be set");
		String type = request.getTargetType();
		if (!"ASSET".equals(type) && !"COLLECTION".equals(type)) {
			throw new ValidationException("Unknown share target type " + type + ". Expected ASSET or COLLECTION");
		}
		requireNonNull(request.getTargetUuid(), "A target uuid must be set");
		if (request.getPassword() != null && request.getPassword().trim().isEmpty()) {
			// An empty string is neither "no password" nor a password. Refusing it here keeps a UI bug from
			// producing a link that looks protected in the dialog and opens for anyone who submits a blank box.
			throw new ValidationException("The password must not be empty. Omit it entirely for an open link");
		}
	}

	default void validate(ShareUpdateRequest request) {
		if (request.getPassword() != null && request.getPassword().trim().isEmpty()) {
			throw new ValidationException("The password must not be empty. Use removePassword to make the link open");
		}
	}

	/**
	 * The share response deliberately does <b>not</b> go through {@code validateCreatorEditorResponse}.
	 *
	 * <p>
	 * That helper requires a creator, and a share may legitimately have none: deleting a user sets {@code share.creator_uuid} to null rather than
	 * removing the link, which is the stated requirement. Validating the shared way here would make every response for an orphaned share a 500 the
	 * first time somebody deleted an account.
	 * </p>
	 */
	default void validate(ShareResponse response) {
		requireNonNull(response, null);
		requireNonNull(response.getUuid(), "A uuid must be set");
		requireNonNullOrEmpty(response.getSlug(), "A slug must be set");
		requireNonNullOrEmpty(response.getTargetType(), "A target type must be set");
	}

	default void validate(ShareSessionRequest request) {
		if (request.getVisitorName() != null && request.getVisitorName().length() > MAX_VISITOR_NAME_LENGTH) {
			throw new ValidationException("The visitor name must be at most " + MAX_VISITOR_NAME_LENGTH + " characters");
		}
	}

	default void validate(ShareCommentRequest request) {
		requireNonNullOrEmpty(request.getText(), "A comment text must be set");
		if (request.getText().length() > MAX_TEXT_LENGTH) {
			throw new ValidationException("The comment must be at most " + MAX_TEXT_LENGTH + " characters");
		}
	}

	default void validate(ShareReactionRequest request) {
		requireNonNullOrEmpty(request.getType(), "A reaction type must be set");
		int subjects = 0;
		if (request.getAssetUuid() != null) {
			subjects++;
		}
		if (request.getCommentUuid() != null) {
			subjects++;
		}
		if (request.getAnnotationUuid() != null) {
			subjects++;
		}
		if (subjects != 1) {
			throw new ValidationException("Exactly one of assetUuid, commentUuid or annotationUuid must be set");
		}
	}

	/**
	 * Reject a mark that does not carry the geometry its kind claims, and coordinates outside the frame.
	 *
	 * <p>
	 * The database enforces both (V2.99), but a constraint violation surfaces as a 500 naming a constraint. Checking here turns the same mistake into
	 * a 400 that says which field is wrong.
	 * </p>
	 */
	default void validate(ShareAnnotationRequest request) {
		requireNonNull(request.getAssetUuid(), "An asset uuid must be set");
		requireNonNullOrEmpty(request.getKind(), "An annotation kind must be set");

		String kind = request.getKind();
		boolean temporal = "TEMPORAL".equals(kind) || "SPATIOTEMPORAL".equals(kind);
		boolean spatial = "SPATIAL".equals(kind) || "SPATIOTEMPORAL".equals(kind);
		if (!temporal && !spatial) {
			throw new ValidationException("Unknown annotation kind " + kind + ". Expected TEMPORAL, SPATIAL or SPATIOTEMPORAL");
		}

		if (temporal) {
			requireNonNull(request.getTimeFrom(), "A " + kind + " annotation needs a timeFrom");
			if (request.getTimeFrom() < 0) {
				throw new ValidationException("timeFrom must not be negative");
			}
			if (request.getTimeTo() != null && request.getTimeTo() < request.getTimeFrom()) {
				throw new ValidationException("timeTo must not be before timeFrom");
			}
		} else if (request.getTimeFrom() != null || request.getTimeTo() != null) {
			throw new ValidationException("A SPATIAL annotation must not carry a timecode");
		}

		if (spatial) {
			requireNonNull(request.getAreaX(), "A " + kind + " annotation needs an areaX");
			requireNonNull(request.getAreaY(), "A " + kind + " annotation needs an areaY");
			requireNonNull(request.getAreaWidth(), "A " + kind + " annotation needs an areaWidth");
			requireNonNull(request.getAreaHeight(), "A " + kind + " annotation needs an areaHeight");
			requireFraction(request.getAreaX(), "areaX", false);
			requireFraction(request.getAreaY(), "areaY", false);
			requireFraction(request.getAreaWidth(), "areaWidth", true);
			requireFraction(request.getAreaHeight(), "areaHeight", true);
		} else if (request.getAreaX() != null || request.getAreaY() != null
			|| request.getAreaWidth() != null || request.getAreaHeight() != null) {
			throw new ValidationException("A TEMPORAL annotation must not carry a region");
		}

		if (request.getText() != null && request.getText().length() > MAX_TEXT_LENGTH) {
			throw new ValidationException("The annotation text must be at most " + MAX_TEXT_LENGTH + " characters");
		}
	}

	/**
	 * Coordinates are fractions of the media's own dimensions, so everything must land in 0..1. Extents must additionally be non-zero: a box of no
	 * width renders as nothing, which reads as a broken viewer rather than as a bad request.
	 */
	private void requireFraction(Double value, String field, boolean mustBePositive) {
		if (value < 0 || value > 1) {
			throw new ValidationException(field + " must be between 0 and 1 - coordinates are fractions of the media size, not pixels");
		}
		if (mustBePositive && value <= 0) {
			throw new ValidationException(field + " must be greater than 0");
		}
	}
}
