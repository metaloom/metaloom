package io.metaloom.cortex.node.source.s3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.node.source.s3.avro.S3ObjectEntry;
import io.metaloom.cortex.s3.S3ObjectRef;

/**
 * Persists an {@link S3ObjectIndex} as a single Avro data file, one record per tracked object.
 *
 * <p>Mirrors {@code AvroFileIndexStore} from the differential filesystem scanner, including the
 * important detail that a <b>missing file loads as an empty index</b> - so the first run of a
 * pipeline naturally reports every object as {@code NEW} rather than failing.</p>
 *
 * <p>Two per-scan values that are not per-object ({@code lastFullScanMillis},
 * {@code lastSeenKey}) ride along as Avro file metadata rather than being duplicated onto every
 * record.</p>
 */
public class S3ObjectIndexStore {

	private static final Logger log = LoggerFactory.getLogger(S3ObjectIndexStore.class);

	private static final String META_LAST_FULL_SCAN = "lastFullScanMillis";
	private static final String META_LAST_SEEN_KEY = "lastSeenKey";

	/**
	 * Load an index.
	 *
	 * @param file the index file
	 * @return the index; empty when the file does not exist or cannot be read
	 */
	public S3ObjectIndex load(Path file) {
		S3ObjectIndex index = new S3ObjectIndex();
		if (file == null || !Files.isRegularFile(file)) {
			return index;
		}

		DatumReader<S3ObjectEntry> datumReader = new SpecificDatumReader<>(S3ObjectEntry.class);
		try (DataFileReader<S3ObjectEntry> reader = new DataFileReader<>(file.toFile(), datumReader)) {
			byte[] lastScan = reader.getMeta(META_LAST_FULL_SCAN);
			if (lastScan != null) {
				index.setLastFullScanMillis(Long.parseLong(new String(lastScan, StandardCharsets.UTF_8)));
			}
			while (reader.hasNext()) {
				S3ObjectEntry entry = reader.next();
				index.put(toRef(entry));
			}
			byte[] lastKey = reader.getMeta(META_LAST_SEEN_KEY);
			if (lastKey != null) {
				index.setLastSeenKey(new String(lastKey, StandardCharsets.UTF_8));
			}
		} catch (Exception e) {
			// A corrupt or half-written index must not wedge the pipeline. Treating it as empty
			// costs one full re-scan and one round of redundant processing, which is recoverable;
			// refusing to start is not.
			log.warn("Could not read the S3 object index at {} - treating it as empty", file, e);
			return new S3ObjectIndex();
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
	public void store(Path file, S3ObjectIndex index) throws IOException {
		Files.createDirectories(file.getParent());

		// Write beside the target and move into place, so an interrupted run leaves the previous
		// index intact rather than a truncated file that would force a full re-scan.
		Path partial = file.resolveSibling(file.getFileName() + ".part");
		DatumWriter<S3ObjectEntry> datumWriter = new SpecificDatumWriter<>(S3ObjectEntry.class);
		try (DataFileWriter<S3ObjectEntry> writer = new DataFileWriter<>(datumWriter)) {
			writer.setCodec(CodecFactory.deflateCodec(CodecFactory.DEFAULT_DEFLATE_LEVEL));
			writer.setMeta(META_LAST_FULL_SCAN, String.valueOf(index.getLastFullScanMillis()));
			if (index.getLastSeenKey() != null) {
				writer.setMeta(META_LAST_SEEN_KEY, index.getLastSeenKey());
			}
			writer.create(S3ObjectEntry.getClassSchema(), partial.toFile());
			for (S3ObjectRef ref : index.values()) {
				writer.append(toEntry(ref));
			}
		}
		Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	private static S3ObjectRef toRef(S3ObjectEntry entry) {
		return new S3ObjectRef(
			entry.getBucket().toString(),
			entry.getKey().toString(),
			entry.getEtag() == null ? null : entry.getEtag().toString(),
			entry.getSize(),
			entry.getLastModifiedMillis());
	}

	private static S3ObjectEntry toEntry(S3ObjectRef ref) {
		return S3ObjectEntry.newBuilder()
			.setBucket(ref.bucket())
			.setKey(ref.key())
			.setEtag(ref.etag())
			.setSize(ref.size())
			.setLastModifiedMillis(ref.lastModifiedMillis())
			.build();
	}
}
