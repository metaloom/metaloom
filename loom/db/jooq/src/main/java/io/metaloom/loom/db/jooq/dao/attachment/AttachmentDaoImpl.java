package io.metaloom.loom.db.jooq.dao.attachment;

import static io.metaloom.loom.db.jooq.tables.JooqAttachment.ATTACHMENT;
import static io.metaloom.loom.db.jooq.tables.JooqAttachmentBinary.ATTACHMENT_BINARY;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqAttachment;
import io.metaloom.loom.db.jooq.tables.records.JooqAttachmentBinaryRecord;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.utils.hash.SHA512;

@Singleton
public class AttachmentDaoImpl extends AbstractJooqDao<Attachment> implements AttachmentDao {

	@Inject
	public AttachmentDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Attachments";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqAttachment.ATTACHMENT;
	}

	@Override
	protected Class<? extends Attachment> getPojoClass() {
		return AttachmentImpl.class;
	}

	@Override
	public Attachment load(UUID uuid) {
		return ctx()
			.select()
			.from(ATTACHMENT)
			.leftJoin(ATTACHMENT_BINARY)
			.on(ATTACHMENT_BINARY.SHA512SUM.eq(ATTACHMENT.BINARY_SHA512SUM))
			.where(ATTACHMENT.UUID.eq(uuid))
			.fetchOneInto(getPojoClass());
	}

	@Override
	public Page<Attachment> loadPage(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.select()
			.from(ATTACHMENT)
			.leftJoin(ATTACHMENT_BINARY)
			.on(ATTACHMENT_BINARY.SHA512SUM.eq(ATTACHMENT.BINARY_SHA512SUM))
			.where();

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public void store(Attachment attachment) {

		// 1. Ensure that binary is stored
		JooqAttachmentBinaryRecord binary = ATTACHMENT_BINARY.newRecord();
		binary.setSha512sum(attachment.getSha512sum().toString());
		binary.setSize(attachment.getSize());
		ctx().insertInto(ATTACHMENT_BINARY)
			.set(binary)
			.onConflictDoNothing()
			.execute();

		TableRecord<?> reco = ctx().newRecord(getTable(), attachment);
		if (attachment.getUuid() == null) {
			reco.reset("uuid");
		}

		UUID uuid = ctx().insertInto(getTable())
			.set(reco)
			.returning(getTable().field("uuid", UUID.class))
			.fetchOne("uuid", UUID.class);
		if (uuid == null) {
			throw new RuntimeException("Key null!");
		}
		attachment.setUuid(uuid);
	}

	@Override
	public Attachment createAttachment(UUID userUuid, SHA512 sha512sum, String filename, long size, String mimeType, AttachmentType type) {
		Attachment attachment = new AttachmentImpl();
		attachment.setFilename(filename);
		attachment.setSize(size);
		attachment.setMimeType(mimeType);
		attachment.setType(type);
		attachment.setSha512sum(sha512sum);
		setCreatorEditor(attachment, userUuid);
		return attachment;
	}
}
