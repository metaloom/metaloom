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
	public Attachment findFaceCrop(UUID detectionUuid, String variant) {
		if (detectionUuid == null) {
			return null;
		}
		org.jooq.Condition condition = ATTACHMENT.DETECTION_UUID.eq(detectionUuid)
			.and(ATTACHMENT.TYPE.eq(io.metaloom.loom.db.jooq.enums.JooqAttachmentType.FACE_CROP));
		if (variant != null) {
			condition = condition.and(ATTACHMENT.VARIANT.eq(variant));
		}
		// The pool lives on attachment_binary, and the download path needs it to resolve the storage backend.
		return ctx()
			.select()
			.from(ATTACHMENT)
			.leftJoin(ATTACHMENT_BINARY)
			.on(ATTACHMENT_BINARY.SHA512SUM.eq(ATTACHMENT.BINARY_SHA512SUM))
			.where(condition)
			.orderBy(ATTACHMENT.CREATED.desc())
			.limit(1)
			.fetchOneInto(getPojoClass());
	}

	@Override
	public List<Attachment> listByPerson(UUID personUuid) {
		if (personUuid == null) {
			return List.of();
		}
		// Same left join as every other read here: the pool lives on attachment_binary and the download path needs it.
		return ctx()
			.select()
			.from(ATTACHMENT)
			.leftJoin(ATTACHMENT_BINARY)
			.on(ATTACHMENT_BINARY.SHA512SUM.eq(ATTACHMENT.BINARY_SHA512SUM))
			.where(ATTACHMENT.PERSON_UUID.eq(personUuid)
				.and(ATTACHMENT.TYPE.eq(io.metaloom.loom.db.jooq.enums.JooqAttachmentType.PERSON_IMAGE)))
			// Newest first, tie-broken by uuid: two images uploaded in the same transaction share a timestamp, and a gallery
			// that reshuffles between two reads is worse than one in an arbitrary but stable order.
			.orderBy(ATTACHMENT.CREATED.desc(), ATTACHMENT.UUID.desc())
			.fetchInto(getPojoClass())
			.stream()
			.map(a -> (Attachment) a)
			.toList();
	}

	@Override
	public Attachment loadAvatarByUser(UUID userUuid) {
		if (userUuid == null) {
			return null;
		}
		// Same left join as every other read here: the pool lives on attachment_binary and the download path needs it.
		// No ordering or limit - the partial unique index (V2.93) guarantees at most one row, and fetchOne turning a
		// second one into an exception is the right way to learn that the index was dropped.
		return ctx()
			.select()
			.from(ATTACHMENT)
			.leftJoin(ATTACHMENT_BINARY)
			.on(ATTACHMENT_BINARY.SHA512SUM.eq(ATTACHMENT.BINARY_SHA512SUM))
			.where(ATTACHMENT.USER_UUID.eq(userUuid)
				.and(ATTACHMENT.TYPE.eq(io.metaloom.loom.db.jooq.enums.JooqAttachmentType.USER_AVATAR)))
			.fetchOneInto(getPojoClass());
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
		binary.setPoolUuid(attachment.getPoolUuid());
		ctx().insertInto(ATTACHMENT_BINARY)
			.set(binary)
			// Content-addressed, so a conflict means these exact bytes are already registered. The
			// existing pool wins: the bytes are physically there, and overwriting the column would
			// point every attachment sharing this hash at a backend that does not hold them.
			.onConflictDoNothing()
			.execute();

		TableRecord<?> reco = ctx().newRecord(getTable(), attachment);
		// pool_uuid lives on attachment_binary, not attachment; newRecord() ignores unknown fields,
		// but being explicit here documents why the attachment row carries no pool of its own.
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
