package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET_BINARY;
import static io.metaloom.loom.db.model.perm.Permission.READ_CLUSTER;
import static io.metaloom.loom.db.model.perm.Permission.READ_DETECTION;
import static io.metaloom.loom.db.model.perm.Permission.READ_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PERSON;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import io.metaloom.loom.db.model.cluster.ClusterMember;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.db.model.person.PersonPrint;
import io.metaloom.loom.db.model.person.PersonPrintDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.ClusterModelBuilder;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.builder.PersonModelBuilder;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.person.PersonAvatarRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonHeroVoiceRequest;
import io.metaloom.loom.rest.model.person.PersonImageImportRequest;
import io.metaloom.loom.rest.model.person.PersonImageListResponse;
import io.metaloom.loom.rest.model.person.PersonPrintCreateRequest;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleImportRequest;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleListResponse;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleResponse;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;
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

	private final StorageCapacityGuard capacityGuard;

	private final AttachmentBinarySender binarySender;

	private final AudioClipService audioClips;

	/**
	 * How much of a speaker's longest turn becomes a sample when the caller names no range. The recognition study enrols with about 30 seconds of
	 * speech; more from one occasion adds little, while a second occasion adds a lot.
	 */
	static final long DEFAULT_VOICE_SAMPLE_MS = 30_000L;

	@Inject
	public PersonEndpointService(PersonDao personDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		AttachmentDao attachmentDao, BinaryStorageResolver storageResolver, StorageCapacityGuard capacityGuard,
		AttachmentBinarySender binarySender, AudioClipService audioClips) {
		super(personDao, daos, modelBuilder, validator);
		this.attachmentDao = attachmentDao;
		this.storageResolver = storageResolver;
		this.capacityGuard = capacityGuard;
		this.binarySender = binarySender;
		this.audioClips = audioClips;
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
			if (response.getData() != null && !response.getData().isEmpty()) {
				modelBuilder.withPersonNames(response.getData(), dao().loadDisplayNames(ClusterModelBuilder.personUuidsOf(response.getData())));
			}
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
			capacityGuard.checkUpload(storage, size);

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
			binarySender.send(lrc, requireImage(personUuid, imageUuid), "image");
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

	// ---------------------------------------------------------------------------------------------
	// Voice samples
	// ---------------------------------------------------------------------------------------------

	/**
	 * {@code GET /api/v1/persons/:uuid/voice-samples} - the person's voice samples, newest first, each with what every voice model made of it.
	 */
	public void listVoiceSamples(LoomRoutingContext lrc, UUID personUuid) {
		checkPerm(lrc, READ_PERSON, () -> {
			Person person = requirePerson(personUuid);
			List<Attachment> samples = attachmentDao.listByPerson(personUuid, AttachmentType.PERSON_VOICE);
			// One query for every sample's prints rather than one per sample.
			Map<UUID, List<PersonPrint>> prints = printDao().listByPerson(personUuid).stream()
				.collect(Collectors.groupingBy(PersonPrint::attachmentUuid));

			PersonVoiceSampleListResponse response = new PersonVoiceSampleListResponse();
			response.setData(samples.stream()
				.map(sample -> modelBuilder.toPersonVoiceSampleResponse(person, sample, prints.getOrDefault(sample.getUuid(), List.of())))
				.toList());
			lrc.send(response);
		});
	}

	/**
	 * {@code POST /api/v1/persons/:uuid/voice-samples} - upload a recording of this person's voice.
	 *
	 * <p>
	 * Audio, or a video whose sound is the voice - a phone clip of somebody speaking is the commonest source. Anything else is refused: a sample is
	 * handed to a voice model, and a picture or a document would only fail there, later and less clearly. The bytes are stored as they came; the
	 * node that embeds a sample decodes it itself.
	 * </p>
	 */
	public void uploadVoiceSample(LoomRoutingContext lrc, UUID personUuid) {
		checkPerm(lrc, UPDATE_PERSON, () -> {
			Person person = requirePerson(personUuid);
			FileUpload upload = singleUpload(lrc);
			String mimeType = upload.contentType();
			if (!isVoiceMimeType(mimeType)) {
				throw new LoomRestException(415, LoomRestErrorCode.BAD_REQUEST,
					"A voice sample must be audio (or a video with a soundtrack), got '" + mimeType + "'.");
			}
			Path file = Paths.get(upload.uploadedFileName());
			JsonObject meta = new JsonObject();
			Long durationMs = audioClips.durationMs(file);
			if (durationMs != null) {
				meta.put(PersonModelBuilder.VOICE_META_DURATION_MS, durationMs);
			}
			Attachment sample = storeVoiceSample(lrc, personUuid, file, upload.fileName(), mimeType, optionalUuid(lrc, "poolUuid"), meta);
			lrc.send(modelBuilder.toPersonVoiceSampleResponse(person, sample, List.of()), 201);
		});
	}

	/**
	 * {@code POST /api/v1/persons/:uuid/voice-samples/from-cluster} - cut a speaker's voice out of the asset it was heard in.
	 *
	 * <p>
	 * Needs {@code READ_CLUSTER} and {@code READ_ASSET_BINARY} alongside {@code UPDATE_PERSON}, for the reason the face-crop import needs
	 * {@code READ_DETECTION}: the result is a downloadable piece of the asset's audio, and a voice is biometric. Without them, editing a person would
	 * be a way to read any recording in the library.
	 * </p>
	 *
	 * <p>
	 * With no range, the speaker's longest uninterrupted turn is taken - up to {@link #DEFAULT_VOICE_SAMPLE_MS} of it. The longest turn is the stretch
	 * least likely to contain somebody else, which matters more for a reference than its length.
	 * </p>
	 */
	public void importVoiceSampleFromCluster(LoomRoutingContext lrc, UUID personUuid) {
		checkPerms(lrc, () -> {
			Person person = requirePerson(personUuid);
			PersonVoiceSampleImportRequest request = lrc.requestBody(PersonVoiceSampleImportRequest.class);
			validator.validate(request);
			UUID clusterUuid = parseUuid(request.getClusterUuid(), "clusterUuid");
			Cluster cluster = daos().clusterDao().load(clusterUuid);
			if (cluster == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Cluster not found.");
			}
			if (!Cluster.TYPE_SPEAKER.equals(cluster.getType()) || cluster.getAssetUuid() == null) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
					"Only a speaker cluster found in an asset holds a voice to cut; this one is '" + cluster.getType() + "'.");
			}

			long[] range = request.getTimeFrom() != null
				? new long[] { request.getTimeFrom(), request.getTimeTo() }
				: longestTurn(daos().clusterDao().listMembers(clusterUuid), DEFAULT_VOICE_SAMPLE_MS);
			if (range == null) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The speaker cluster has no timed members to cut a sample from.");
			}

			Path clip = audioClips.cut(cluster.getAssetUuid(), range[0], range[1]);
			try {
				JsonObject meta = new JsonObject()
					.put(PersonModelBuilder.VOICE_META_DURATION_MS, range[1] - range[0])
					.put(PersonModelBuilder.VOICE_META_SOURCE, new JsonObject()
						.put("assetUuid", cluster.getAssetUuid().toString())
						.put("clusterUuid", clusterUuid.toString())
						.put("timeFrom", range[0])
						.put("timeTo", range[1]));
				String filename = "voice-" + clusterUuid + "-" + range[0] + ".wav";
				Attachment sample = storeVoiceSample(lrc, personUuid, clip, filename, "audio/wav", null, meta);
				lrc.send(modelBuilder.toPersonVoiceSampleResponse(person, sample, List.of()), 201);
			} finally {
				try {
					Files.deleteIfExists(clip);
				} catch (IOException e) {
					log.debug("Could not delete the temporary clip {}", clip, e);
				}
			}
		}, UPDATE_PERSON, READ_CLUSTER, READ_ASSET_BINARY);
	}

	/**
	 * {@code GET /api/v1/persons/:uuid/voice-samples/:sampleUuid/data} - the recording.
	 */
	public void downloadVoiceSample(LoomRoutingContext lrc, UUID personUuid, UUID sampleUuid) {
		checkPerm(lrc, READ_PERSON, () -> {
			binarySender.send(lrc, requireSample(personUuid, sampleUuid, AttachmentType.PERSON_VOICE), "voice sample");
		});
	}

	/**
	 * {@code DELETE /api/v1/persons/:uuid/voice-samples/:sampleUuid}. Its prints go with it (V2.115), and a person whose hero sample it was is left
	 * without one.
	 */
	public void deleteVoiceSample(LoomRoutingContext lrc, UUID personUuid, UUID sampleUuid) {
		checkPerm(lrc, UPDATE_PERSON, () -> {
			requireSample(personUuid, sampleUuid, AttachmentType.PERSON_VOICE);
			attachmentDao.delete(sampleUuid);
			lrc.sendNoContent();
		});
	}

	/**
	 * {@code POST /api/v1/persons/:uuid/hero-voice} - which of the person's samples represents their voice. A blank {@code sampleUuid} clears it.
	 */
	public void setHeroVoice(LoomRoutingContext lrc, UUID personUuid) {
		checkPerm(lrc, UPDATE_PERSON, () -> {
			Person person = requirePerson(personUuid);
			PersonHeroVoiceRequest request = lrc.requestBody(PersonHeroVoiceRequest.class);
			validator.validate(request);
			String raw = request.getSampleUuid();
			if (raw == null || raw.isBlank()) {
				person.setHeroVoiceAttachmentUuid(null);
			} else {
				UUID sampleUuid = parseUuid(raw, "sampleUuid");
				requireSample(personUuid, sampleUuid, AttachmentType.PERSON_VOICE);
				person.setHeroVoiceAttachmentUuid(sampleUuid);
			}
			setEditor(person, lrc.userUuid());
			dao().update(person);
			lrc.send(modelBuilder.toResponse(person));
		});
	}

	/**
	 * {@code POST /api/v1/persons/:uuid/voice-samples/:sampleUuid/prints} and {@code .../images/:imageUuid/prints} - store what a model made of one
	 * of the person's samples.
	 *
	 * <p>
	 * Written by the node that owns the model, never by a person. The type must match the sample: a voice is only comparable with speaker clusters,
	 * a picture only with face clusters, and a print filed under the wrong one would be compared with vectors from another modality.
	 * </p>
	 */
	public void storePrint(LoomRoutingContext lrc, UUID personUuid, UUID sampleUuid, AttachmentType sampleType) {
		checkPerm(lrc, UPDATE_PERSON, () -> {
			requireSample(personUuid, sampleUuid, sampleType);
			PersonPrintCreateRequest request = lrc.requestBody(PersonPrintCreateRequest.class);
			validator.validate(request);
			String expected = printTypeFor(sampleType);
			if (!expected.equals(request.getType())) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
					"A print of this sample is compared with '" + expected + "' clusters, not '" + request.getType() + "'.");
			}
			String error = request.getVector() == null ? request.getError().trim() : null;
			PersonPrint print = printDao().upsert(personUuid, sampleUuid, expected, request.getModel(), request.getVector(), error);
			lrc.send(modelBuilder.toResponse(print), 201);
		});
	}

	/** The cluster type a print of a sample of this attachment type is comparable with. */
	public static String printTypeFor(AttachmentType sampleType) {
		return sampleType == AttachmentType.PERSON_IMAGE ? Cluster.TYPE_FACE : Cluster.TYPE_SPEAKER;
	}

	/**
	 * The longest stretch in which the speaker talks without a gap, capped at {@code capMs} from its start.
	 *
	 * <p>
	 * Speech windows overlap (a 3 s window every 1.5 s), so consecutive windows of one turn touch or overlap; a gap between two windows is where
	 * somebody else spoke or nobody did. Returns null when no member carries a time window.
	 * </p>
	 */
	static long[] longestTurn(List<ClusterMember> members, long capMs) {
		List<long[]> windows = new ArrayList<>();
		for (ClusterMember member : members) {
			if (member.getTimeFrom() != null && member.getTimeTo() != null && member.getTimeTo() > member.getTimeFrom()) {
				windows.add(new long[] { member.getTimeFrom(), member.getTimeTo() });
			}
		}
		if (windows.isEmpty()) {
			return null;
		}
		windows.sort(Comparator.comparingLong(w -> w[0]));
		long[] best = null;
		long[] current = windows.get(0).clone();
		for (int i = 1; i < windows.size(); i++) {
			long[] w = windows.get(i);
			if (w[0] <= current[1]) {
				current[1] = Math.max(current[1], w[1]);
			} else {
				best = longer(best, current);
				current = w.clone();
			}
		}
		best = longer(best, current);
		return new long[] { best[0], Math.min(best[1], best[0] + capMs) };
	}

	private static long[] longer(long[] a, long[] b) {
		return a == null || (b[1] - b[0]) > (a[1] - a[0]) ? b : a;
	}

	private static boolean isVoiceMimeType(String mimeType) {
		if (mimeType == null) {
			return false;
		}
		String lower = mimeType.toLowerCase(Locale.ROOT);
		return lower.startsWith("audio/") || lower.startsWith("video/");
	}

	/** Store the bytes before the row, so a sample never points at content that is not there. */
	private Attachment storeVoiceSample(LoomRoutingContext lrc, UUID personUuid, Path file, String filename, String mimeType, UUID poolUuid,
		JsonObject meta) {
		long size;
		try {
			size = Files.size(file);
		} catch (IOException e) {
			throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Could not read the voice sample: " + e.getMessage());
		}
		SHA512 sha512sum = HashUtils.computeSHA512(file);
		BinaryStorage storage = storageResolver.forPool(poolUuid);
		capacityGuard.checkUpload(storage, size);
		storage.store(file, sha512sum, mimeType);

		Attachment sample = attachmentDao.createAttachment(lrc.userUuid(), sha512sum, filename, size, mimeType, AttachmentType.PERSON_VOICE);
		sample.setPoolUuid(poolUuid);
		sample.setPersonUuid(personUuid);
		sample.setMeta(meta);
		attachmentDao.store(sample);
		log.info("Stored voice sample {} ({} bytes, {}) for person {} in {}", filename, size, mimeType, personUuid, storage.describe());
		return sample;
	}

	private PersonPrintDao printDao() {
		return daos().personPrintDao();
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

	/**
	 * Load one of the person's samples of the given type and prove it belongs to the person in the path - answered as missing otherwise, for the
	 * reason {@link #requireImage} gives.
	 */
	private Attachment requireSample(UUID personUuid, UUID sampleUuid, AttachmentType type) {
		requirePerson(personUuid);
		Attachment sample = attachmentDao.load(sampleUuid);
		if (sample == null || sample.getSha512sum() == null || !personUuid.equals(sample.getPersonUuid()) || sample.getType() != type) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
				type == AttachmentType.PERSON_VOICE ? "Voice sample not found." : "Person image not found.");
		}
		return sample;
	}

	private static String fileNameFor(Attachment crop, UUID detectionUuid) {
		String filename = crop.getFilename();
		return filename == null || filename.isBlank() ? "face-" + detectionUuid + ".jpg" : filename;
	}

}
