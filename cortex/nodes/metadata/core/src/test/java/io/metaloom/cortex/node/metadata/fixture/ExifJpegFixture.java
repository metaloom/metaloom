package io.metaloom.cortex.node.metadata.fixture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a JPEG carrying a real EXIF APP1 segment, and optionally a real XMP packet.
 *
 * <p>
 * The alternative was a committed binary fixture, which nobody can review and nobody can vary. This
 * is a few hundred bytes of well-understood structure, so a test that needs "a photo shot in the
 * southern hemisphere with no lens tag" writes exactly that instead of hunting for a sample file -
 * and the EXIF layout is documented once, here, rather than being folklore.
 * </p>
 *
 * <p>
 * The image data is deliberately absent: the file is {@code SOI · APP1 · EOI}. Every metadata reader
 * stops at the start-of-scan marker, so pixels would only make the fixture bigger.
 * </p>
 */
public final class ExifJpegFixture {

	private static final int TYPE_ASCII = 2;
	private static final int TYPE_SHORT = 3;
	private static final int TYPE_LONG = 4;
	private static final int TYPE_RATIONAL = 5;

	private static final int TAG_EXIF_IFD_POINTER = 0x8769;
	private static final int TAG_GPS_IFD_POINTER = 0x8825;

	private final List<Field> ifd0 = new ArrayList<>();
	private final List<Field> exifIfd = new ArrayList<>();
	private final List<Field> gpsIfd = new ArrayList<>();

	private String xmpPacket;

	public static ExifJpegFixture builder() {
		return new ExifJpegFixture();
	}

	// ---- IFD0: the tags a camera writes about the image itself ----

	public ExifJpegFixture imageDescription(String value) {
		return ifd0Ascii(0x010E, value);
	}

	public ExifJpegFixture make(String value) {
		return ifd0Ascii(0x010F, value);
	}

	public ExifJpegFixture model(String value) {
		return ifd0Ascii(0x0110, value);
	}

	public ExifJpegFixture orientation(int value) {
		ifd0.add(Field.shortValue(0x0112, value));
		return this;
	}

	public ExifJpegFixture software(String value) {
		return ifd0Ascii(0x0131, value);
	}

	public ExifJpegFixture artist(String value) {
		return ifd0Ascii(0x013B, value);
	}

	public ExifJpegFixture copyright(String value) {
		return ifd0Ascii(0x8298, value);
	}

	// ---- Exif SubIFD: the exposure ----

	/** {@code yyyy:MM:dd HH:mm:ss} - EXIF's own format, which is not ISO-8601. */
	public ExifJpegFixture dateTimeOriginal(String value) {
		exifIfd.add(Field.ascii(0x9003, value));
		return this;
	}

	/** {@code +09:00}. EXIF 2.31 and later; without it the date has no timezone at all. */
	public ExifJpegFixture offsetTimeOriginal(String value) {
		exifIfd.add(Field.ascii(0x9011, value));
		return this;
	}

	public ExifJpegFixture exposureTime(int numerator, int denominator) {
		exifIfd.add(Field.rational(0x829A, numerator, denominator));
		return this;
	}

	public ExifJpegFixture fNumber(int numerator, int denominator) {
		exifIfd.add(Field.rational(0x829D, numerator, denominator));
		return this;
	}

	public ExifJpegFixture iso(int value) {
		exifIfd.add(Field.shortValue(0x8827, value));
		return this;
	}

	public ExifJpegFixture focalLength(int numerator, int denominator) {
		exifIfd.add(Field.rational(0x920A, numerator, denominator));
		return this;
	}

	/** The raw EXIF bit field: bit 0 is "fired". */
	public ExifJpegFixture flash(int value) {
		exifIfd.add(Field.shortValue(0x9209, value));
		return this;
	}

	public ExifJpegFixture lensModel(String value) {
		exifIfd.add(Field.ascii(0xA434, value));
		return this;
	}

	// ---- GPS IFD ----

	/**
	 * Write a position as EXIF stores it: unsigned degrees/minutes/seconds plus a hemisphere
	 * reference tag. Negative arguments become the S / W reference - the conversion that a
	 * hand-rolled reader most often gets wrong.
	 */
	public ExifJpegFixture gps(double latitude, double longitude) {
		gpsIfd.add(Field.ascii(0x0001, latitude >= 0 ? "N" : "S"));
		gpsIfd.add(Field.rationals(0x0002, dms(Math.abs(latitude))));
		gpsIfd.add(Field.ascii(0x0003, longitude >= 0 ? "E" : "W"));
		gpsIfd.add(Field.rationals(0x0004, dms(Math.abs(longitude))));
		return this;
	}

	public ExifJpegFixture altitude(double meters) {
		gpsIfd.add(Field.byteValue(0x0005, meters >= 0 ? 0 : 1));
		gpsIfd.add(Field.rational(0x0006, (int) Math.round(Math.abs(meters) * 100), 100));
		return this;
	}

	public ExifJpegFixture imgDirection(double degrees) {
		gpsIfd.add(Field.rational(0x0011, (int) Math.round(degrees * 100), 100));
		return this;
	}

	public ExifJpegFixture positioningError(double meters) {
		gpsIfd.add(Field.rational(0x001F, (int) Math.round(meters * 100), 100));
		return this;
	}

	/**
	 * Attach an XMP packet as a second APP1 segment - the shape a photo editor writes.
	 */
	public ExifJpegFixture xmp(String rdfXml) {
		this.xmpPacket = rdfXml;
		return this;
	}

	private ExifJpegFixture ifd0Ascii(int tag, String value) {
		ifd0.add(Field.ascii(tag, value));
		return this;
	}

	/** Degrees, minutes and seconds, the last carrying the fraction at 1/10000 precision. */
	private static long[][] dms(double degrees) {
		int wholeDegrees = (int) degrees;
		double remainder = (degrees - wholeDegrees) * 60;
		int minutes = (int) remainder;
		double seconds = (remainder - minutes) * 60;
		return new long[][] {
			{ wholeDegrees, 1 },
			{ minutes, 1 },
			{ Math.round(seconds * 10000), 10000 } };
	}

	public byte[] build() throws IOException {
		ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
		jpeg.write(new byte[] { (byte) 0xFF, (byte) 0xD8 }); // SOI
		writeApp1(jpeg, exifSegmentPayload());
		if (xmpPacket != null) {
			ByteArrayOutputStream xmp = new ByteArrayOutputStream();
			xmp.write("http://ns.adobe.com/xap/1.0/".getBytes(StandardCharsets.US_ASCII));
			xmp.write(0);
			xmp.write(xmpPacket.getBytes(StandardCharsets.UTF_8));
			writeApp1(jpeg, xmp.toByteArray());
		}
		jpeg.write(new byte[] { (byte) 0xFF, (byte) 0xD9 }); // EOI
		return jpeg.toByteArray();
	}

	private static void writeApp1(ByteArrayOutputStream out, byte[] payload) throws IOException {
		int length = payload.length + 2; // the length field counts itself
		out.write(new byte[] { (byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length });
		out.write(payload);
	}

	/**
	 * {@code "Exif\0\0"} followed by a TIFF structure: header, IFD0, then the Exif and GPS sub-IFDs
	 * that IFD0 points at.
	 */
	private byte[] exifSegmentPayload() throws IOException {
		List<Field> root = new ArrayList<>(ifd0);
		int ifd0Offset = 8;
		int ifd0Size = sizeOf(root.size() + (exifIfd.isEmpty() ? 0 : 1) + (gpsIfd.isEmpty() ? 0 : 1), root);
		int nextOffset = ifd0Offset + ifd0Size;

		if (!exifIfd.isEmpty()) {
			root.add(Field.longValue(TAG_EXIF_IFD_POINTER, nextOffset));
			nextOffset += sizeOf(exifIfd.size(), exifIfd);
		}
		if (!gpsIfd.isEmpty()) {
			root.add(Field.longValue(TAG_GPS_IFD_POINTER, nextOffset));
		}

		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write("MM".getBytes(StandardCharsets.US_ASCII)); // big endian
		tiff.write(new byte[] { 0x00, 0x2A });
		writeInt(tiff, ifd0Offset);
		tiff.write(encodeIfd(root, ifd0Offset));
		int offset = ifd0Offset + ifd0Size;
		if (!exifIfd.isEmpty()) {
			tiff.write(encodeIfd(exifIfd, offset));
			offset += sizeOf(exifIfd.size(), exifIfd);
		}
		if (!gpsIfd.isEmpty()) {
			tiff.write(encodeIfd(gpsIfd, offset));
		}

		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write("Exif".getBytes(StandardCharsets.US_ASCII));
		payload.write(0);
		payload.write(0);
		payload.write(tiff.toByteArray());
		return payload.toByteArray();
	}

	/**
	 * An IFD is {@code count · entries · nextOffset}, followed by the values too large to sit in an
	 * entry's four-byte slot. The size has to be known before anything is written, because the
	 * pointer entries in IFD0 hold the absolute offsets of the IFDs that follow it.
	 */
	private static int sizeOf(int entryCount, List<Field> fields) {
		int size = 2 + 12 * entryCount + 4;
		for (Field field : fields) {
			if (field.payload.length > 4) {
				size += field.payload.length + (field.payload.length % 2);
			}
		}
		return size;
	}

	private static byte[] encodeIfd(List<Field> fields, int ifdOffset) throws IOException {
		List<Field> sorted = new ArrayList<>(fields);
		// The TIFF spec requires entries in ascending tag order; some readers rely on it.
		sorted.sort(Comparator.comparingInt(f -> f.tag));

		ByteArrayOutputStream entries = new ByteArrayOutputStream();
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		int dataOffset = ifdOffset + 2 + 12 * sorted.size() + 4;

		writeShort(entries, sorted.size());
		for (Field field : sorted) {
			writeShort(entries, field.tag);
			writeShort(entries, field.type);
			writeInt(entries, field.count);
			if (field.payload.length <= 4) {
				byte[] inline = new byte[4];
				System.arraycopy(field.payload, 0, inline, 0, field.payload.length);
				entries.write(inline);
			} else {
				writeInt(entries, dataOffset + data.size());
				data.write(field.payload);
				if (field.payload.length % 2 != 0) {
					data.write(0); // values are word-aligned
				}
			}
		}
		writeInt(entries, 0); // no next IFD

		ByteArrayOutputStream ifd = new ByteArrayOutputStream();
		ifd.write(entries.toByteArray());
		ifd.write(data.toByteArray());
		return ifd.toByteArray();
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private static void writeInt(ByteArrayOutputStream out, int value) {
		out.write((value >> 24) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	/** One IFD entry: tag, type, element count, and the value bytes. */
	private record Field(int tag, int type, int count, byte[] payload) {

		static Field ascii(int tag, String value) {
			byte[] bytes = (value + "\0").getBytes(StandardCharsets.US_ASCII);
			return new Field(tag, TYPE_ASCII, bytes.length, bytes);
		}

		static Field shortValue(int tag, int value) {
			// A SHORT sits in the high half of the four-byte slot on a big-endian file.
			return new Field(tag, TYPE_SHORT, 1, new byte[] { (byte) (value >> 8), (byte) value });
		}

		static Field byteValue(int tag, int value) {
			return new Field(tag, 1, 1, new byte[] { (byte) value });
		}

		static Field longValue(int tag, int value) {
			return new Field(tag, TYPE_LONG, 1, new byte[] {
				(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value });
		}

		static Field rational(int tag, int numerator, int denominator) {
			return rationals(tag, new long[][] { { numerator, denominator } });
		}

		static Field rationals(int tag, long[][] values) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (long[] value : values) {
				writeInt(out, (int) value[0]);
				writeInt(out, (int) value[1]);
			}
			return new Field(tag, TYPE_RATIONAL, values.length, out.toByteArray());
		}
	}
}
