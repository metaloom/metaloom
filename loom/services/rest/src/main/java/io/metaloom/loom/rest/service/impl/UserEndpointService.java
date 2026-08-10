package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_USER;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_USER;
import static io.metaloom.loom.db.model.perm.Permission.READ_USER;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_USER;

import java.nio.file.Paths;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.ext.web.FileUpload;

@Singleton
public class UserEndpointService extends AbstractCRUDEndpointService<UserDao, User> {

	private static final Logger log = LoggerFactory.getLogger(UserEndpointService.class);

	private final AttachmentDao attachmentDao;

	private final BinaryStorageResolver storageResolver;

	private final StorageCapacityGuard capacityGuard;

	private final AttachmentBinarySender binarySender;

	@Inject
	public UserEndpointService(UserDao userDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		AttachmentDao attachmentDao, BinaryStorageResolver storageResolver, StorageCapacityGuard capacityGuard,
		AttachmentBinarySender binarySender) {
		super(userDao, daos, modelBuilder, validator);
		this.attachmentDao = attachmentDao;
		this.storageResolver = storageResolver;
		this.capacityGuard = capacityGuard;
		this.binarySender = binarySender;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, DELETE_USER, () -> {
			UUID userUuid = lrc.userUuid();
			User user = dao().load(uuid);
			// Deleting a user is a soft delete that purges their personal data rather than removing the row, which
			// exists so foreign keys still resolve. A picture of somebody's face is personal data in exactly that
			// sense, so the attachment row goes with the rest of it - markDeleted() can only null the pointer,
			// having no DAO of its own. Deleted first so the FK (ON DELETE SET NULL) does the nulling.
			Attachment avatar = attachmentDao.loadAvatarByUser(uuid);
			if (avatar != null) {
				attachmentDao.delete(avatar.getUuid());
				user.setAvatarAttachmentUuid(null);
			}
			user.markDeleted();
			setEditor(user, userUuid);
			return user;
		}, modelBuilder::toResponse);
	}

	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_USER, () -> {
			UserCreateRequest request = lrc.requestBody(UserCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String userName = request.getUsername();
			// TODO handle conflicts
			User user = dao().createUser(userUuid, userName);
			update(request::getMeta, user::setMeta);
			setEditor(user, userUuid);
			return user;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_USER, () -> {
			UserUpdateRequest request = lrc.requestBody(UserUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			User user = dao().load(uuid);
			if (user == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "User not found.");
			}
			update(request::getUsername, user::setUsername);
			update(request::getFirstname, user::setFirstname);
			update(request::getLastname, user::setLastname);
			update(request::getEmail, user::setEmail);
			update(request::getMeta, user::setMeta);
			setEditor(user, userUuid);
			return user;
		}, modelBuilder::toResponse);
	}

	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_USER, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	/**
	 * Load the currently authenticated user. No READ_USER permission is required - any authenticated user may read their own record. Authentication
	 * is enforced by the endpoint's secure() handler.
	 */
	public void me(LoomRoutingContext lrc) {
		User user = dao().load(lrc.userUuid());
		if (user == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "User not found.");
		}
		lrc.send(modelBuilder.toResponse(user));
	}

	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_USER, modelBuilder::toUserList);
	}

	// ---------------------------------------------------------------------------------------------
	// The account picture
	//
	// Four routes, mounted twice: under /users/:uuid for administrators and under /me for the account
	// itself. There is exactly one picture per account - a partial unique index enforces it (V2.93) -
	// so an upload replaces rather than appends, and there is no "which one is the avatar" concept.
	// That is the difference from a person, who has a gallery because face detection keeps finding
	// them in new material.
	// ---------------------------------------------------------------------------------------------

	/**
	 * {@code GET /api/v1/users/:uuid/avatar} - the account's picture, as JSON.
	 */
	public void loadAvatar(LoomRoutingContext lrc, UUID userUuid) {
		checkAvatarPerm(lrc, userUuid, READ_USER, () -> {
			User user = requireUser(userUuid);
			lrc.send(modelBuilder.toUserAvatarResponse(user, requireAvatar(userUuid)));
		});
	}

	/**
	 * {@code GET /api/v1/users/:uuid/avatar/data} - the picture bytes.
	 */
	public void downloadAvatar(LoomRoutingContext lrc, UUID userUuid) {
		checkAvatarPerm(lrc, userUuid, READ_USER, () -> {
			binarySender.send(lrc, requireAvatar(userUuid), "avatar");
		});
	}

	/**
	 * {@code POST /api/v1/users/:uuid/avatar} - upload the account's picture, replacing any previous one.
	 *
	 * <p>
	 * An account has no parent asset to inherit a storage pool from, so the bytes land in the default local storage unless the caller names a
	 * {@code poolUuid}.
	 * </p>
	 */
	public void uploadAvatar(LoomRoutingContext lrc, UUID userUuid) {
		checkAvatarPerm(lrc, userUuid, UPDATE_USER, () -> {
			User user = requireUser(userUuid);
			FileUpload upload = singleUpload(lrc);

			String filename = upload.fileName();
			long size = upload.size();
			String mimeType = upload.contentType();
			SHA512 sha512sum = HashUtils.computeSHA512(Paths.get(upload.uploadedFileName()));
			UUID poolUuid = optionalUuid(lrc, "poolUuid");
			BinaryStorage storage = storageResolver.forPool(poolUuid);
			capacityGuard.checkUpload(storage, size);

			// Store before the row exists, so an avatar never points at content that is not there.
			storage.store(Paths.get(upload.uploadedFileName()), sha512sum, mimeType);

			// Drop the previous picture first. The partial unique index would reject the insert otherwise, and the
			// FK from user.avatar_attachment_uuid is ON DELETE SET NULL, so this leaves the pointer null rather than
			// dangling for the moment between the two writes.
			Attachment previous = attachmentDao.loadAvatarByUser(userUuid);
			if (previous != null) {
				attachmentDao.delete(previous.getUuid());
			}

			Attachment avatar = attachmentDao.createAttachment(lrc.userUuid(), sha512sum, filename, size, mimeType, AttachmentType.USER_AVATAR);
			avatar.setPoolUuid(poolUuid);
			avatar.setUserUuid(userUuid);
			attachmentDao.store(avatar);

			// The FK cycle resolves in this order only: the attachment row must exist before the user can point at it.
			user.setAvatarAttachmentUuid(avatar.getUuid());
			setEditor(user, lrc.userUuid());
			dao().update(user);
			log.info("Stored avatar {} ({} bytes, {}) for user {} in {}", filename, size, mimeType, userUuid, storage.describe());

			lrc.send(modelBuilder.toUserAvatarResponse(user, avatar), 201);
		});
	}

	/**
	 * {@code DELETE /api/v1/users/:uuid/avatar} - remove the account's picture.
	 */
	public void deleteAvatar(LoomRoutingContext lrc, UUID userUuid) {
		checkAvatarPerm(lrc, userUuid, UPDATE_USER, () -> {
			// The FK nulls user.avatar_attachment_uuid for us (ON DELETE SET NULL), so there is no window in which
			// the pointer references a deleted row.
			attachmentDao.delete(requireAvatar(userUuid).getUuid());
			lrc.sendNoContent();
		});
	}

	/**
	 * A user may always read and change their own picture; touching somebody else's is an administrative act.
	 *
	 * <p>
	 * Without the self-exemption the profile screen would work for administrators only: {@code UPDATE_USER} is the permission to edit <em>any</em>
	 * account, and no ordinary user holds it. {@code /me} already serves the same record without requiring {@code READ_USER} for exactly this reason.
	 * </p>
	 */
	private void checkAvatarPerm(LoomRoutingContext lrc, UUID target, Permission adminPerm, Runnable action) {
		if (target != null && target.equals(lrc.userUuid())) {
			action.run();
			return;
		}
		checkPerm(lrc, adminPerm, action);
	}

	private User requireUser(UUID userUuid) {
		User user = dao().load(userUuid);
		if (user == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "User not found.");
		}
		return user;
	}

	private Attachment requireAvatar(UUID userUuid) {
		requireUser(userUuid);
		Attachment avatar = attachmentDao.loadAvatarByUser(userUuid);
		if (avatar == null || avatar.getSha512sum() == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "This user has no avatar.");
		}
		return avatar;
	}

}
