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
		lrc.requirePerm(permission).onSuccess(l -> {
			action.run();
		}).onFailure(e -> {
			// TODO this should be 500 error
			log.error("Failed to check perms", e);
			throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Invalid permissions");
		});
	}

}
