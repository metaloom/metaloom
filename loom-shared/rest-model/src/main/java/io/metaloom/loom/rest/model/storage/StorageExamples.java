package io.metaloom.loom.rest.model.storage;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * OpenAPI examples for the storage report.
 *
 * <p>
 * The categories named here are the real vocabulary, and the second backend is a real S3 pool with null capacity figures - which is the case a client
 * is most likely to get wrong, and the one an example that only ever showed a filesystem would hide.
 * </p>
 */
public interface StorageExamples extends ExampleValues {

	default Example storageReportExample() {
		return new ExampleImpl(storageReport(), "The storage report", HttpResponseStatus.OK);
	}

	default Example storageBackendListExample() {
		return new ExampleImpl(storageBackendList(), "The storage backends and their capacity", HttpResponseStatus.OK);
	}

	default StorageThresholdsModel storageThresholds() {
		return new StorageThresholdsModel()
			.setMinFreeSpaceBytes(1073741824L)
			.setWarnFreeSpaceBytes(5368709120L)
			.setMaxUploadSizeBytes(-1L);
	}

	default StorageBackendModel localBackend() {
		return new StorageBackendModel()
			.setPoolUuid(null)
			.setPoolName("Default storage")
			.setKind("filesystem")
			.setDescription("filesystem:/uploads")
			.setFreeBytes(48318382080L)
			.setTotalBytes(214748364800L)
			.setWatermark("OK")
			.setObjects(1842)
			.setBytes(160890470400L);
	}

	default StorageBackendModel bucketBackend() {
		return new StorageBackendModel()
			.setPoolUuid(UUID.fromString("6c1f7b1e-0d0a-4b3a-9f7c-2f1d3c4b5a60"))
			.setPoolName("Archive S3")
			.setKind("s3")
			.setDescription("s3:metaloom-archive-prod")
			// Null, not zero: a bucket has no capacity to report, which is why the watermark is UNKNOWN rather than OK.
			.setFreeBytes(null)
			.setTotalBytes(null)
			.setWatermark("UNKNOWN")
			.setObjects(9431)
			.setBytes(2199023255552L);
	}

	default StorageReportResponse storageReport() {
		return new StorageReportResponse()
			.setTimestamp(Instant.parse("2026-08-10T09:14:22Z"))
			.setThresholds(storageThresholds())
			.add(new StorageCategoryModel()
				.setCategory("ASSET_BINARY")
				.setElements(1204)
				.setLogicalBytes(163208757248L)
				.setDistinctObjects(1198)
				.setDistinctBytes(162987212800L))
			.add(new StorageCategoryModel()
				.setCategory("FACE_CROP")
				.setElements(8842)
				.setLogicalBytes(371654656L)
				.setDistinctObjects(8391)
				.setDistinctBytes(352845824L))
			.add(new StorageCategoryModel()
				.setCategory("PERSON_IMAGE")
				.setElements(112)
				.setLogicalBytes(9437184L)
				.setDistinctObjects(104)
				.setDistinctBytes(8650752L))
			.add(new StorageCategoryModel()
				.setCategory("PERSON_AVATAR")
				.setElements(37)
				.setLogicalBytes(3145728L)
				.setDistinctObjects(37)
				.setDistinctBytes(3145728L))
			.add(new StorageCategoryModel()
				.setCategory("USER_AVATAR")
				.setElements(9)
				.setLogicalBytes(524288L)
				.setDistinctObjects(9)
				.setDistinctBytes(524288L))
			.add(new StorageCategoryModel()
				.setCategory("ASSET_THUMBNAIL")
				.setElements(0)
				.setLogicalBytes(0)
				.setDistinctObjects(0)
				.setDistinctBytes(0))
			.add(new StorageCategoryModel()
				.setCategory("EMBEDDING_ATTACHMENT")
				.setElements(0)
				.setLogicalBytes(0)
				.setDistinctObjects(0)
				.setDistinctBytes(0))
			.add(localBackend())
			.add(bucketBackend())
			.setObjects(8532)
			.setDistinctBytes(361955328L)
			.setOrphanObjects(148)
			.setOrphanBytes(6291456L);
	}

	default StorageBackendListResponse storageBackendList() {
		return new StorageBackendListResponse()
			.setThresholds(storageThresholds())
			.add(localBackend())
			.add(bucketBackend());
	}
}
