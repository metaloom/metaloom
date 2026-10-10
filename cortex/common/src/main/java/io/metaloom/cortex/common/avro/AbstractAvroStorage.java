package io.metaloom.cortex.common.avro;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

import io.metaloom.utils.hash.SHA512;

public abstract class AbstractAvroStorage<T extends SpecificRecord> {

	protected void writeElements(SHA512 hash, T... elements) throws IOException {
		writeElements(hash, List.of(elements));
	}

	protected void writeElements(SHA512 hash, List<T> elements) throws IOException {
		Objects.requireNonNull(hash);
		Objects.requireNonNull(elements);
		File file = getFileForHash(hash);

		DatumWriter<T> datumWriter = new SpecificDatumWriter<T>(getElementClass());
		try (DataFileWriter<T> dataFileWriter = new DataFileWriter<>(datumWriter)) {
			// create() opens the file once. Calling it per element - as this used to - failed on
			// the second element with "already open", and an empty list never created the file at
			// all, so readElements() then failed instead of returning nothing.
			Schema schema = elements.isEmpty() ? SpecificData.get().getSchema(getElementClass()) : elements.get(0).getSchema();
			dataFileWriter.create(schema, file);
			for (T element : elements) {
				dataFileWriter.append(element);
			}
		}
	}

	protected List<T> readElements(SHA512 hash) throws IOException {
		File file = getFileForHash(hash);
		List<T> detections = new ArrayList<>();
		DatumReader<T> datumReader = new SpecificDatumReader<>(getElementClass());
		try (DataFileReader<T> dataFileReader = new DataFileReader<>(file, datumReader)) {
			T fd = null;
			while (dataFileReader.hasNext()) {
				fd = dataFileReader.next();
				detections.add(fd);
			}
		}
		return detections;
	}

	protected abstract File getFileForHash(SHA512 hash);

	protected abstract Class<T> getElementClass();

}
