package io.metaloom.loom.db.jooq.dao.failure;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.model.failure.FailureReportScreenshot;

public class FailureReportScreenshotImpl implements FailureReportScreenshot {

	private UUID reportUuid;
	private String mimeType;
	private Integer width;
	private Integer height;
	private Long size;
	private byte[] data;
	private Instant created;

	@Override
	public UUID getReportUuid() {
		return reportUuid;
	}

	@Override
	public FailureReportScreenshot setReportUuid(UUID reportUuid) {
		this.reportUuid = reportUuid;
		return this;
	}

	@Override
	public String getMimeType() {
		return mimeType;
	}

	@Override
	public FailureReportScreenshot setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	@Override
	public Integer getWidth() {
		return width;
	}

	@Override
	public FailureReportScreenshot setWidth(Integer width) {
		this.width = width;
		return this;
	}

	@Override
	public Integer getHeight() {
		return height;
	}

	@Override
	public FailureReportScreenshot setHeight(Integer height) {
		this.height = height;
		return this;
	}

	@Override
	public Long getSize() {
		return size;
	}

	@Override
	public FailureReportScreenshot setSize(Long size) {
		this.size = size;
		return this;
	}

	@Override
	public byte[] getData() {
		return data;
	}

	@Override
	public FailureReportScreenshot setData(byte[] data) {
		this.data = data;
		return this;
	}

	@Override
	public Instant getCreated() {
		return created;
	}

	@Override
	public FailureReportScreenshot setCreated(Instant created) {
		this.created = created;
		return this;
	}
}
