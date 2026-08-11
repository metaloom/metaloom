package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.metaloom.loom.rest.model.share.ShareResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class ShareModelAssert extends AbstractModelAssert<ShareModelAssert, ShareResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public ShareModelAssert(ShareResponse actual) {
		super(actual, ShareModelAssert.class);
	}

	public ShareModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public ShareModelAssert hasSlug(String slug) {
		assertEquals(slug, actual.getSlug(), "The slug did not match");
		return this;
	}

	/**
	 * Assert the slug is shaped the way {@code ShareSessionTokens#generateSlug()} makes them.
	 *
	 * <p>
	 * The dot matters as much as the length: {@code UIService} routes a {@code /ui/*} path whose last segment carries an extension to the static file
	 * handler, so a slug containing one would 404 instead of opening the app.
	 * </p>
	 */
	public ShareModelAssert hasWellFormedSlug() {
		assertNotNull(actual.getSlug(), "No slug was set");
		assertEquals(22, actual.getSlug().length(), "A slug is 128 bits of base64url, i.e. 22 characters");
		assertTrue(actual.getSlug().matches("[A-Za-z0-9_-]{22}"),
			"A slug must be base64url and must not contain a dot: " + actual.getSlug());
		return this;
	}

	public ShareModelAssert isPasswordProtected() {
		assertTrue(Boolean.TRUE.equals(actual.getPasswordProtected()), "The share should be password protected");
		return this;
	}

	public ShareModelAssert isOpen() {
		assertFalse(Boolean.TRUE.equals(actual.getPasswordProtected()), "The share should not be password protected");
		return this;
	}

	/**
	 * Assert the password is not on the wire.
	 *
	 * <p>
	 * Only the bcrypt hash is stored, so any response other than the one that set it must leave this null. A response that carried it would mean the
	 * clear password had been persisted.
	 * </p>
	 */
	public ShareModelAssert hidesThePassword() {
		assertNull(actual.getPassword(), "Only the response that sets a password may carry it");
		return this;
	}

	public ShareModelAssert isExpired() {
		assertTrue(Boolean.TRUE.equals(actual.getExpired()), "The share should be expired");
		return this;
	}

	public ShareModelAssert isNotExpired() {
		assertFalse(Boolean.TRUE.equals(actual.getExpired()), "The share should not be expired");
		return this;
	}

	public ShareModelAssert targets(String targetType, java.util.UUID targetUuid) {
		assertEquals(targetType, actual.getTargetType(), "The target type did not match");
		assertEquals(targetUuid, actual.getTargetUuid(), "The target uuid did not match");
		return this;
	}

	/**
	 * Assert the link is pasteable into an email rather than root-relative.
	 */
	public ShareModelAssert hasAbsoluteUrl() {
		assertNotNull(actual.getUrl(), "No share URL was set");
		assertTrue(actual.getUrl().startsWith("http"), "The share URL must be absolute: " + actual.getUrl());
		assertTrue(actual.getUrl().endsWith("/ui/share/" + actual.getSlug()), "Unexpected share URL " + actual.getUrl());
		return this;
	}

	public ShareModelAssert hasViewCount(int count) {
		assertEquals(Integer.valueOf(count), actual.getViewCount(), "The view count did not match");
		return this;
	}

	public ShareModelAssert hasVisitorName(String name) {
		assertEquals(name, actual.getVisitorName(), "The visitor name did not match");
		return this;
	}

	public ShareModelAssert hasFeedbackCount(int count) {
		assertEquals(Integer.valueOf(count), actual.getFeedbackCount(), "The feedback count did not match");
		return this;
	}
}
