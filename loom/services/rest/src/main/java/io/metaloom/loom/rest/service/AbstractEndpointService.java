package io.metaloom.loom.rest.service;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Consumer;
import io.vertx.ext.web.FileUpload;

public abstract class AbstractEndpointService implements EndpointService {

	private static final Logger log = LoggerFactory.getLogger(AbstractEndpointService.class);

	protected final LoomModelBuilder modelBuilder;
	protected final LoomModelValidator validator;

	public AbstractEndpointService(LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		this.modelBuilder = modelBuilder;
		this.validator = validator;
	}

	protected void setEditor(CUDElement<?> element, UUID userUuid) {
		element.setEditorUuid(userUuid);
		element.setEdited(Instant.now());
	}

	// TODO maybe add validation parameter?
	protected <T> void update(Supplier<T> getter, Consumer<T> setter) {
		T value = getter.get();
		if (value != null) {
			setter.accept(value);
		}
	}

	protected void checkPerm(LoomRoutingContext lrc, Permission permission, Runnable action) {
		checkPerms(lrc, action, permission);
	}

	/**
	 * All-or-nothing variant of {@link #checkPerm(LoomRoutingContext, Permission, Runnable)} for routes whose required permission set depends on the
	 * request. The action runs only when the caller holds every listed permission.
	 *
	 * <p>
	 * The {@link Runnable} comes before the permissions because varargs must be last; call sites needing a single fixed permission should keep using
	 * {@code checkPerm}, which reads better.
	 * </p>
	 */
	protected void checkPerms(LoomRoutingContext lrc, Runnable action, Permission... permissions) {
		lrc.requirePerm(permissions).onSuccess(l -> {
			action.run();
		}).onFailure(e -> {
			// TODO this should be 500 error
			log.error("Failed to check perms", e);
			throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Invalid permissions");
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Multipart form helpers
	//
	// Shared by every route that accepts bytes - asset uploads, attachments, person images, user
	// avatars. They lived privately in three services with three slightly different error messages
	// until the fourth copy was about to be written.
	// ---------------------------------------------------------------------------------------------

	/**
	 * Return the single uploaded file part, or fail with a 400 when there are zero or more than one.
	 */
	protected FileUpload singleUpload(LoomRoutingContext lrc) {
		if (lrc.fileUploads().isEmpty()) {
			throw new LoomRestException(400, LoomRestErrorCode.UPLOAD_DATA_MISSING, "No uploads found in request.");
		}
		if (lrc.fileUploads().size() > 1) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"Upload with multiple files in one request is currently not supported");
		}
		return lrc.fileUploads().get(0);
	}

	/**
	 * Parse a required uuid-valued field.
	 *
	 * @throws LoomRestException 400 when the value is blank or not a uuid. A malformed uuid is a bad request rather than an internal error — without
	 *                           this it surfaces as a 500 from deep inside the DAO.
	 */
	protected UUID parseUuid(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The '" + field + "' is required.");
		}
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The '" + field + "' is not a valid uuid.");
		}
	}

	/**
	 * An optional uuid-valued form field.
	 *
	 * <p>
	 * Absent and blank both mean "not given", so a form that always emits the field still works. A non-blank value that is not a uuid is a 400 rather
	 * than a silent fall-back, so a typo cannot quietly route bytes somewhere else.
	 * </p>
	 */
	protected UUID optionalUuid(LoomRoutingContext lrc, String field) {
		return optionalUuid(lrc.routingContext().request().getFormAttribute(field), field);
	}

	/**
	 * Parse an optional uuid-valued string, typically a JSON body field.
	 *
	 * <p>
	 * Null and blank both mean "not given" and return null; only a non-blank value that is not a uuid is a 400. That is the difference from
	 * {@link #parseUuid(String, String)}, which treats blank as missing-and-required.
	 * </p>
	 */
	protected UUID optionalUuid(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return parseUuid(raw, field);
	}

	/** A required uuid-valued form field. */
	protected UUID requiredUuid(LoomRoutingContext lrc, String field) {
		return parseUuid(lrc.routingContext().request().getFormAttribute(field), field);
	}

	/** A form field with a fall-back for absent and blank. */
	protected String formValue(LoomRoutingContext lrc, String field, String defaultValue) {
		String value = lrc.routingContext().request().getFormAttribute(field);
		return (value == null || value.isBlank()) ? defaultValue : value.trim();
	}

}
