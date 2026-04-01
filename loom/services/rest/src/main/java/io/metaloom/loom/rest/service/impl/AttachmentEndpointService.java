package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.READ_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ATTACHMENT;

import java.nio.file.Paths;
import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.attachment.AttachmentUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.ext.web.FileUpload;

public class AttachmentEndpointService extends AbstractCRUDEndpointService<AttachmentDao, Attachment> {

	private static final Logger log = LoggerFactory.getLogger(AttachmentEndpointService.class);

	@Inject
	public AttachmentEndpointService(AttachmentDao attachmentDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(attachmentDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_ATTACHMENT, uuid);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_ATTACHMENT, modelBuilder::toAttachmentList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_ATTACHMENT, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_ATTACHMENT, () -> {
			if (lrc.fileUploads().size() > 1) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
					"Upload with multiple files in one request is currently not supported");
			}
			if (lrc.fileUploads().isEmpty()) {
				throw new LoomRestException(400, LoomRestErrorCode.UPLOAD_DATA_MISSING, "No uploads found in request.");
			}
			FileUpload upload = lrc.fileUploads().get(0);
			// validator.validate(upload);
			// TODO check for free space
			// TODO configure upload directory
			// TODO copy / move file into place
			// TODO segment the sha512sum into subfolder structure (3 levels)
			UUID userUuid = lrc.userUuid();
			String filename = upload.fileName();
			long size = upload.size();
			String mimeType = upload.contentType();
			AttachmentType type = AttachmentType.EMBEDDING_ATTACHMENT;
			SHA512 sha512sum = HashUtils.computeSHA512(Paths.get(upload.uploadedFileName()));
			Attachment attachment = dao().createAttachment(userUuid, sha512sum, filename, size, mimeType, type);
			return attachment;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_ATTACHMENT, () -> {
			AttachmentUpdateRequest request = lrc.requestBody(AttachmentUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Attachment attachment = dao().load(id);
			update(request::getFilename, attachment::setFilename);
			update(request::getMimeType, attachment::setMimeType);
			update(request::getMeta, attachment::setMeta);
			setEditor(attachment, userUuid);
			return attachment;
		}, modelBuilder::toResponse);
	}
}
