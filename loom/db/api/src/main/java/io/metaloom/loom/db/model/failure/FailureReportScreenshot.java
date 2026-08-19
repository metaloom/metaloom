package io.metaloom.loom.db.model.failure;

import java.time.Instant;
import java.util.UUID;

/**
 * The optional screenshot attached to a {@link FailureReport}.
 *
 * <p>
 * <b>A row of its own, not a column on the report</b>, because the inbox lists reports and never wants the bytes. {@code AbstractJooqDao} issues
 * {@code SELECT *}, so a megabyte-wide column on the listed table would turn every page of the inbox into a multi-megabyte read for data nobody asked
 * for.
 * </p>
 *
 * <p>
 * <b>Not a {@code Element}</b>: it has no uuid of its own and is addressed entirely through its report. Giving it one would suggest it can be moved or
 * shared between reports, which it cannot - the relationship is 1:1 and the report uuid is the primary key.
 * </p>
 */
public interface FailureReportScreenshot {

	/** The report this belongs to. Also the primary key. */
	UUID getReportUuid();

	FailureReportScreenshot setReportUuid(UUID reportUuid);

	/** Always an image type, and always one the service allowlisted - see {@code FailureReportEndpointService}. */
	String getMimeType();

	FailureReportScreenshot setMimeType(String mimeType);

	/** Pixel width as the client reported it, or null when it did not. Used only to size the preview. */
	Integer getWidth();

	FailureReportScreenshot setWidth(Integer width);

	/** Pixel height as the client reported it, or null when it did not. */
	Integer getHeight();

	FailureReportScreenshot setHeight(Integer height);

	/** Size of {@link #getData()} in bytes, denormalised so a listing can show it without reading the blob. */
	Long getSize();

	FailureReportScreenshot setSize(Long size);

	/** The image itself. */
	byte[] getData();

	FailureReportScreenshot setData(byte[] data);

	Instant getCreated();

	FailureReportScreenshot setCreated(Instant created);
}
