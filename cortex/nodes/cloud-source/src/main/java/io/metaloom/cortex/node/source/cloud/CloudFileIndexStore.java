package io.metaloom.cortex.node.source.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.node.source.cloud.avro.CloudFileEntry;

/**
 * Persists a {@link CloudFileIndex} as a single Avro data file, one record per tracked item.
 *
 * <p>Mirrors {@code S3ObjectIndexStore}, including the property that matters most: a
 * <b>missing or corrupt file loads as an empty index</b>. That is what makes a first run report
 * everything as {@code NEW} rather than failing, and what stops a half-written file from wedging a
 * pipeline - the cost is one redundant walk, which is recoverable.</p>
 *
 * <p>The three per-scan values that are not per-file ride along as Avro file metadata rather than
 * being duplicated onto every record.</p>
 */
public class CloudFileIndexStore {

	private static final Logger log = LoggerFactory.getLogger(CloudFileIndexStore.class);

	private static final String META_LAST_FULL_SCAN = "lastFullScanMillis";
	private static final String META_DELTA_TOKEN = "deltaToken";
	private static final String META_ACCOUNT_ID = "accountId";

	/**
	 * Load an index.
	 *
	 * @param file the index file
	 * @return the index; empty when the file does not exist or cannot be read
	 */
	public CloudFileIndex load(Path file) {
		CloudFileIndex index = new CloudFileIndex();
		if (file == null || !Files.isRegularFile(file)) {
			return index;
		}

		DatumReader<CloudFileEntry> datumReader = new SpecificDatumReader<>(CloudFileEntry.class);
		try (DataFileReader<CloudFileEntry> reader = new DataFileReader<>(file.toFile(), datumReader)) {
			index.setLastFullScanMillis(readLong(reader.getMeta(META_LAST_FULL_SCAN)));
			index.setDeltaToken(readString(reader.getMeta(META_DELTA_TOKEN)));
			index.setAccountId(readString(reader.getMeta(META_ACCOUNT_ID)));
			while (reader.hasNext()) {
				index.put(toRef(reader.next()));
			}
		} catch (Exception e) {
			log.warn("Could not read the cloud file index at {} - treating it as empty", file, e);
			return new CloudFileIndex();
		}
		return index;
	}

	/**
	 * Persist an index, replacing whatever was there.
	 *
	 * @param file  the index file; parent directories are created
	 * @param index the index
	 * @throws IOException when the file cannot be written
	 */
	public void store(Path file, CloudFileIndex index) throws IOException {
		Files.createDirectories(file.getParent());

		// Write beside the target and move into place, so an interrupted run leaves the previous
		// index intact rather than a truncated file that would force a full re-walk.
		Path partial = file.resolveSibling(file.getFileName() + ".part");
		DatumWriter<CloudFileEntry> datumWriter = new SpecificDatumWriter<>(CloudFileEntry.class);
		try (DataFileWriter<CloudFileEntry> writer = new DataFileWriter<>(datumWriter)) {
			writer.setCodec(CodecFactory.deflateCodec(CodecFactory.DEFAULT_DEFLATE_LEVEL));
			writer.setMeta(META_LAST_FULL_SCAN, String.valueOf(index.getLastFullScanMillis()));
			if (index.getDeltaToken() != null) {
				writer.setMeta(META_DELTA_TOKEN, index.getDeltaToken());
			}
			if (index.getAccountId() != null) {
				writer.setMeta(META_ACCOUNT_ID, index.getAccountId());
			}
			writer.create(CloudFileEntry.getClassSchema(), partial.toFile());
			for (CloudFileRef ref : index.values()) {
				writer.append(toEntry(ref));
			}
		}
		Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static long readLong(byte[] value) {
		if (value == null) {
			return 0;
		}
		try {
			return Long.parseLong(new String(value, StandardCharsets.UTF_8));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String readString(byte[] value) {
		return value == null ? null : new String(value, StandardCharsets.UTF_8);
	}

	private static CloudFileRef toRef(CloudFileEntry entry) {
		return new CloudFileRef(
			CloudProviderId.valueOf(entry.getProvider().toString()),
			entry.getDriveId().toString(),
			entry.getFileId().toString(),
			entry.getName().toString(),
			text(entry.getParentId()),
			text(entry.getMimeType()),
			text(entry.getChangeToken()),
			entry.getSize(),
			entry.getLastModifiedMillis(),
			entry.getFolder(),
			// Trashed items are never indexed - either they were filtered out on the way in, or
			// they arrived as a removal - so the recorded state is always "not trashed".
			false,
			text(entry.getExportMimeType()),
			true);
	}

	private static CloudFileEntry toEntry(CloudFileRef ref) {
		return CloudFileEntry.newBuilder()
			.setProvider(ref.provider().name())
			.setDriveId(ref.driveId())
			.setFileId(ref.fileId())
			.setName(ref.name())
			.setParentId(ref.parentId())
			.setMimeType(ref.mimeType())
			.setChangeToken(ref.changeToken())
			.setSize(ref.size())
			.setLastModifiedMillis(ref.lastModifiedMillis())
			.setExportMimeType(ref.exportMimeType())
			.setFolder(ref.folder())
			.build();
	}

	private static String text(CharSequence value) {
		return value == null ? null : value.toString();
	}
}
