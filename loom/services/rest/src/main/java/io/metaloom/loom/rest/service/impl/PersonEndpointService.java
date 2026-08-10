package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.READ_DETECTION;
import static io.metaloom.loom.db.model.perm.Permission.READ_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PERSON;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.person.PersonAvatarRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonImageImportRequest;
import io.metaloom.loom.rest.model.person.PersonImageListResponse;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.FileUpload;

/**
 * Persons, and the pictures they own.
 *
 * <p>
 * A person's images are attachments owned by the person rather than by any asset (V2.90), which is what lets somebody keep their picture when the
 * material they were found in is deleted. One of them is designated the avatar. The whole gallery therefore lives under {@code /persons/:uuid/images}
 * rather than under {@code /attachments}: the person is what scopes access to it.
 * </p>
 *
 * <p>
 * Permissions reuse {@code READ_PERSON} and {@code UPDATE_PERSON}. A person's pictures are part of the person - who may look at them and who may
 * change them is the same trust decision as for their name - so a separate pair of permission values would only be a second lever for the same door.
 * </p>
 */
@Singleton
public class PersonEndpointService extends AbstractCRUDEndpointService<PersonDao, Person> {

	private static final Logger log = LoggerFactory.getLogger(PersonEndpointService.class);

	private final AttachmentDao attachmentDao;

	private final BinaryStorageResolver storageResolver;

	@Inject
	public PersonEndpointService(PersonDao personDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		AttachmentDao attachmentDao, BinaryStorageResolver storageResolver) {
		super(personDao, daos, modelBuilder, validator);
		this.attachmentDao = attachmentDao;
		this.storageResolver = storageResolver;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_PERSON, id);
		// The person's images cascade with them at the DB level (V2.90). Their bytes are deliberately not reclaimed, for the same reason
		// AttachmentEndpointService gives: attachment_binary is content-addressed and shared, and no reference count spans it and asset_location.
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_PERSON, modelBuilder::toPersonList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_PERSON, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_PERSON, () -> {
			PersonCreateRequest request = lrc.requestBody(PersonCreateRequest.class);
			validator.validate(request);

			String alias = request.getAlias();
			UUID userUuid = lrc.userUuid();
			Person person = dao().createPerson(userUuid, alias);
			update(request::getFirstname, person::setFirstname);
			update(request::getLastname, person::setLastname);
			update(request::getMeta, person::setMeta);
			return person;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_PERSON, () -> {
			PersonUpdateRequest request = lrc.requestBody(PersonUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Person person = dao().load(id);
			update(request::getAlias, person::setAlias);
			update(request::getFirstname, person::setFirstname);
			update(request::getLastname, person::setLastname);
			update(request::getMeta, person::setMeta);
			setEditor(person, userUuid);
			return person;
		}, modelBuilder::toResponse);
	}

	/**
	 * {@code GET /api/v1/persons/:uuid/clusters} - every cluster confirmed to be this person.
	 *
	 * <p>
	 * The inverse of the confirmation: given a person, which groups of faces were attributed to them, across which assets.
	 * </p>
	 */
	public void listClusters(LoomRoutingContext lrc, UUID personUuid) {
		checkPerm(lrc, READ_PERSON, () -> {
			if (dao().load(personUuid) == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Person not found.");
			}
			List<Cluster> clusters = daos().clusterDao().findByPerson(personUuid);
			Map<UUID, Long> counts = daos().clusterDao().countMembers(clusters.stream().map(Cluster::getUuid).toList());

			ClusterListResponse response = new ClusterListResponse();
			clusters.forEach(cluster -> response.add(modelBuilder.toResponse(cluster, counts.getOrDefault(cluster.getUuid(), 0L))));
			lrc.send(response);
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Person images
	// ---------------------------------------------------------------------------------------------

	/**
	 * {@code GET /api/v1/persons/:uuid/images} - the person's pictures, newest first.
	 */
	public void listImages(LoomRoutingContext lrc, UUID personUuid) {
		checkPerm(lrc, READ_PERSON, () -> {
			Person person = requirePerson(personUuid);
			List<Attachment> images = attachmentDao.listByPerson(personUuid);

			// setData rather than repeated add: every person starts with no pictures, so an empty gallery is the ordinary state here and the response
			// must still carry an array for the caller to iterate.
			PersonImageListResponse response = new PersonImageListResponse();
			response.setData(images.stream().map(image -> modelBuilder.toPersonImageResponse(person, image)).toList());
			lrc.send(response);
		});
	}

	/**
	 * {@code POST /api/v1/persons/:uuid/images} - upload a picture of this person.
	 *
	 * <p>
	 * The bytes go to the same content-addressed storage an asset binary would use, but the row references no asset at all. With no parent asset there
	 * is nothing to derive a pool from, so an upload lands in the default local storage unless the caller names a {@code poolUuid}.
	 * </p>
	 */
	public void uploadImage(LoomRoutingContext lrc, UUID personUuid) {
		checkPerm(lrc, UPDATE_PERSON, () -> {
			Person person = requirePerson(personUuid);
			FileUpload upload = singleUpload(lrc);

			String filename = upload.fileName();
			long size = upload.size();
			String mimeType = upload.contentType();
			SHA512 sha512sum = HashUtils.computeSHA512(Paths.get(upload.uploadedFileName()));
			UUID poolUuid = optionalUuid(lrc, "poolUuid");
			BinaryStorage storage = storageResolver.forPool(poolUuid);

			// Store before the row exists, so an image never points at content that is not there.
			storage.store(Paths.get(upload.uploadedFileName()), sha512sum, mimeType);

			Attachment image = attachmentDao.createAttachment(lrc.userUuid(), sha512sum, filename, size, mimeType, AttachmentType.PERSON_IMAGE);
			image.setPoolUuid(poolUuid);
			image.setPersonUuid(personUuid);
			attachmentDao.store(image);
			log.info("Stored image {} ({} bytes, {}) for person {} in {}", filename, size, mimeType, personUuid, storage.describe());

			lrc.send(modelBuilder.toPersonImageResponse(person, image), 201);
		});
	}

	/**
	 * {@code POST /api/v1/persons/:uuid/images/from-detection} - take a copy of a detection's face crop into this person's images.
	 *
	 * <p>
	 * A copy, not a reference. The new row carries the same content-addressed hash, so no bytes are duplicated, but it belongs to the person - which
	 * is the whole point: the crop it was taken from dies with its detection and its asset, and the person's picture must not.
	 * </p>
	 *
	 * <p>
	 * Needs {@code READ_DETECTION} alongside {@code UPDATE_PERSON}. The result is a copy of a face crop that the caller can then download from the
	 * person, so requiring only the person permission would turn this into a way to read crops for anybody who may edit a person - which is not what
	 * either permission grants on its own. Face crops are biometric.
	 * </p>
	 */
	public void importImageFromDetection(LoomRoutingContext lrc, UUID personUuid) {
		checkPerms(lrc, () -> {
			Person person = requirePerson(personUuid);
			PersonImageImportRequest request = lrc.requestBody(PersonImageImportRequest.class);
			validator.validate(request);
			UUID detectionUuid = parseUuid(request.getDetectionUuid(), "detectionUuid");

			Attachment crop = attachmentDao.findFaceCrop(detectionUuid, null);
			if (crop == null || crop.getSha512sum() == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"No face crop has been stored for this detection. Crops are written by the face-detection node when it runs.");
			}

			Attachment image = attachmentDao.createAttachment(lrc.userUuid(), crop.getSha512sum(), fileNameFor(crop, detectionUuid), crop.getSize(),
				crop.getMimeType(), AttachmentType.PERSON_IMAGE);
			// The same pool as the crop: the bytes are already there under this hash, and pointing the copy at a different backend would point it at
			// storage that does not hold them.
			image.setPoolUuid(crop.getPoolUuid());
			image.setPersonUuid(personUuid);
			attachmentDao.store(image);

			lrc.send(modelBuilder.toPersonImageResponse(person, image), 201);
		}, UPDATE_PERSON, READ_DETECTION);
	}

	/**
	 * {@code GET /api/v1/persons/:uuid/images/:imageUuid/data} - the image bytes.
	 */
	public void downloadImage(LoomRoutingContext lrc, UUID personUuid, UUID imageUuid) {
		checkPerm(lrc, READ_PERSON, () -> {
			Attachment image = requireImage(personUuid, imageUuid);

			BinaryStorage storage = storageResolver.forPool(image.getPoolUuid());
			String locator = storage.locatorFor(image.getSha512sum());
			if (!storage.exists(locator)) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"The image's bytes are missing in " + storage.describe() + ".");
			}

			HttpServerResponse response = lrc.routingContext().response();
			// The row is immutable once written and the bytes are addressed by their own hash, so this is safe to cache hard.
			String etag = "\"" + imageUuid + "-" + image.getSha512sum().toString().substring(0, 16) + "\"";
			if (etag.equals(lrc.routingContext().request().getHeader(HttpHeaders.IF_NONE_MATCH))) {
				response.setStatusCode(304).end();
				return;
			}
			response.putHeader(HttpHeaders.CONTENT_TYPE, image.getMimeType() == null ? "image/jpeg" : image.getMimeType());
			response.putHeader(HttpHeaders.ETAG, etag);
			// private: a picture of an identified person is not something a shared cache should hold.
			response.putHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=86400, immutable");

			Optional<Path> local = storage.localPath(locator);
			if (local.isPresent()) {
				response.sendFile(local.get().toString());
				return;
			}
			long size = storage.size(locator);
			if (size >= 0) {
				response.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));
			} else {
				// Vert.x refuses a write with neither a Content-Length nor chunked encoding.
				response.setChunked(true);
			}
			try (InputStream in = storage.read(locator, 0, -1)) {
				byte[] buffer = new byte[64 * 1024];
				int read;
				while ((read = in.read(buffer)) > 0) {
					response.write(Buffer.buffer(java.util.Arrays.copyOf(buffer, read)));
				}
				response.end();
			} catch (Exception e) {
				log.error("Failed to stream image {} of person {}", imageUuid, personUuid, e);
				if (!response.headWritten()) {
					throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Could not read the image.");
				}
			}
		});
	}

	/**
	 * {@code DELETE /api/v1/persons/:uuid/images/:imageUuid}.
	 *
	 * <p>
	 * Deleting the image a person is currently shown by is allowed and simply leaves them without an avatar - the FK is {@code ON DELETE SET NULL}
	 * (V2.90), so the database does the nulling and there is no window in which the pointer dangles.
	 * </p>
	 */
	public void deleteImage(LoomRoutingContext lrc, UUID personUuid, UUID imageUuid) {
		checkPerm(lrc, UPDATE_PERSON, () -> {
			requireImage(personUuid, imageUuid);
			attachmentDao.delete(imageUuid);
			lrc.sendNoContent();
		});
	}

	/**
	 * {@code POST /api/v1/persons/:uuid/avatar} - designate which of the person's images is their avatar.
	 *
	 * <p>
	 * A null or blank {@code imageUuid} clears it. Only the person's own images qualify: an avatar that could point anywhere would reintroduce exactly
	 * the problem {@code primary_image_uuid} had, where a person's picture could be a whole video file.
	 * </p>
	 */
	public void setAvatar(LoomRoutingContext lrc, UUID personUuid) {
		checkPerm(lrc, UPDATE_PERSON, () -> {
			Person person = requirePerson(personUuid);
			PersonAvatarRequest request = lrc.requestBody(PersonAvatarRequest.class);
			validator.validate(request);

			String raw = request.getImageUuid();
			if (raw == null || raw.isBlank()) {
				person.setAvatarAttachmentUuid(null);
			} else {
				UUID imageUuid = parseUuid(raw, "imageUuid");
				requireImage(personUuid, imageUuid);
				person.setAvatarAttachmentUuid(imageUuid);
			}
			setEditor(person, lrc.userUuid());
			dao().update(person);

			lrc.send(modelBuilder.toResponse(person));
		});
	}

	// --- Helper methods ---

	private Person requirePerson(UUID personUuid) {
		Person person = dao().load(personUuid);
		if (person == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Person not found.");
		}
		return person;
	}

	/**
	 * Load an image and prove it belongs to the person in the path.
	 *
	 * <p>
	 * An image belonging to somebody else is answered as missing rather than as forbidden: the pairing is part of the address, and confirming that the
	 * uuid exists elsewhere leaks it.
	 * </p>
	 */
	private Attachment requireImage(UUID personUuid, UUID imageUuid) {
		requirePerson(personUuid);
		Attachment image = attachmentDao.load(imageUuid);
		if (image == null || image.getSha512sum() == null || !personUuid.equals(image.getPersonUuid())
			|| image.getType() != AttachmentType.PERSON_IMAGE) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Person image not found.");
		}
		return image;
	}

	private static String fileNameFor(Attachment crop, UUID detectionUuid) {
		String filename = crop.getFilename();
		return filename == null || filename.isBlank() ? "face-" + detectionUuid + ".jpg" : filename;
	}

	private static UUID parseUuid(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The '" + field + "' is required.");
		}
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException e) {
			// A malformed uuid is a bad request, not an internal error - without this it surfaces as a 500 from deep inside the DAO.
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The '" + field + "' is not a valid uuid.");
		}
	}

	private UUID optionalUuid(LoomRoutingContext lrc, String field) {
		String value = lrc.routingContext().request().getFormAttribute(field);
		if (value == null || value.isBlank()) {
			return null;
		}
		return parseUuid(value, field);
	}

	private FileUpload singleUpload(LoomRoutingContext lrc) {
		if (lrc.fileUploads().isEmpty()) {
			throw new LoomRestException(400, LoomRestErrorCode.UPLOAD_DATA_MISSING, "No uploads found in request.");
		}
		if (lrc.fileUploads().size() > 1) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"Upload with multiple files in one request is currently not supported");
		}
		return lrc.fileUploads().get(0);
	}

}
