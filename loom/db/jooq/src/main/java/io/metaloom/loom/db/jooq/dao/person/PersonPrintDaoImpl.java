package io.metaloom.loom.db.jooq.dao.person;

import static io.metaloom.loom.db.jooq.tables.JooqAttachment.ATTACHMENT;
import static io.metaloom.loom.db.jooq.tables.JooqPersonPrint.PERSON_PRINT;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.jooq.enums.JooqAttachmentType;
import io.metaloom.loom.db.model.person.PersonPrint;
import io.metaloom.loom.db.model.person.PersonPrintDao;
import io.metaloom.loom.db.model.person.PersonReference;

/**
 * jOOQ implementation of {@link PersonPrintDao}.
 */
@Singleton
public class PersonPrintDaoImpl implements PersonPrintDao {

	private final DSLContext ctx;

	@Inject
	public PersonPrintDaoImpl(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public String getTypeName() {
		return "PersonPrints";
	}

	@Override
	public void clear() {
		ctx.deleteFrom(PERSON_PRINT).execute();
	}

	@Override
	public long count() {
		return ctx.fetchCount(PERSON_PRINT);
	}

	@Override
	public PersonPrint upsert(UUID personUuid, UUID attachmentUuid, String type, String model, Float[] vector, String error) {
		if ((vector == null) == (error == null)) {
			// The table's CHECK says the same thing; failing here names the mistake instead of a constraint.
			throw new IllegalArgumentException("A print carries either a vector or the error explaining why there is none, never both or neither");
		}
		Integer dimensions = vector == null ? null : vector.length;
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		return ctx.insertInto(PERSON_PRINT)
			.set(PERSON_PRINT.PERSON_UUID, personUuid)
			.set(PERSON_PRINT.ATTACHMENT_UUID, attachmentUuid)
			.set(PERSON_PRINT.TYPE, type)
			.set(PERSON_PRINT.MODEL, model)
			.set(PERSON_PRINT.DIMENSIONS, dimensions)
			.set(PERSON_PRINT.VECTOR, vector)
			.set(PERSON_PRINT.ERROR, error)
			.set(PERSON_PRINT.CREATED, now)
			.set(PERSON_PRINT.EDITED, now)
			// Re-embedding a sample in a space it already has a print in replaces the print: a model fix or a retried error must not leave two
			// answers for one sample, and the unique key is exactly that identity.
			.onConflict(PERSON_PRINT.ATTACHMENT_UUID, PERSON_PRINT.TYPE, PERSON_PRINT.MODEL)
			.doUpdate()
			.set(PERSON_PRINT.PERSON_UUID, personUuid)
			.set(PERSON_PRINT.DIMENSIONS, dimensions)
			.set(PERSON_PRINT.VECTOR, vector)
			.set(PERSON_PRINT.ERROR, error)
			.set(PERSON_PRINT.EDITED, now)
			.returning()
			.fetchOne(this::map);
	}

	@Override
	public List<PersonPrint> listBySample(UUID attachmentUuid) {
		return ctx.selectFrom(PERSON_PRINT)
			.where(PERSON_PRINT.ATTACHMENT_UUID.eq(attachmentUuid))
			.orderBy(PERSON_PRINT.TYPE, PERSON_PRINT.MODEL)
			.fetch(this::map);
	}

	@Override
	public List<PersonPrint> listByPerson(UUID personUuid) {
		return ctx.selectFrom(PERSON_PRINT)
			.where(PERSON_PRINT.PERSON_UUID.eq(personUuid))
			.orderBy(PERSON_PRINT.CREATED, PERSON_PRINT.UUID)
			.fetch(this::map);
	}

	@Override
	public List<PersonReference> listUsableReferences(String type, String model) {
		if (type == null || model == null) {
			return List.of();
		}
		return ctx.select(PERSON_PRINT.PERSON_UUID, PERSON_PRINT.ATTACHMENT_UUID, PERSON_PRINT.VECTOR)
			.from(PERSON_PRINT)
			.where(PERSON_PRINT.TYPE.eq(type)
				.and(PERSON_PRINT.MODEL.eq(model))
				.and(PERSON_PRINT.VECTOR.isNotNull()))
			.fetch(r -> new PersonReference(r.get(PERSON_PRINT.PERSON_UUID), r.get(PERSON_PRINT.ATTACHMENT_UUID), PersonReference.SOURCE_SAMPLE,
				r.get(PERSON_PRINT.VECTOR)));
	}

	@Override
	public List<PendingSample> listUnenrolled(String type, String model, AttachmentType sampleType, int limit) {
		if (type == null || model == null || sampleType == null || limit <= 0) {
			return List.of();
		}
		// NOT EXISTS over any outcome, usable or not: a sample that failed in this space has been tried, and must not come back on every item.
		return ctx.select(ATTACHMENT.PERSON_UUID, ATTACHMENT.UUID, ATTACHMENT.MIME_TYPE)
			.from(ATTACHMENT)
			.where(ATTACHMENT.TYPE.eq(JooqAttachmentType.lookupLiteral(sampleType.name()))
				.and(ATTACHMENT.PERSON_UUID.isNotNull())
				.andNotExists(DSL.selectOne()
					.from(PERSON_PRINT)
					.where(PERSON_PRINT.ATTACHMENT_UUID.eq(ATTACHMENT.UUID)
						.and(PERSON_PRINT.TYPE.eq(type))
						.and(PERSON_PRINT.MODEL.eq(model)))))
			.orderBy(ATTACHMENT.CREATED.asc(), ATTACHMENT.UUID.asc())
			.limit(limit)
			.fetch(r -> new PendingSample(r.get(ATTACHMENT.PERSON_UUID), r.get(ATTACHMENT.UUID), r.get(ATTACHMENT.MIME_TYPE)));
	}

	private PersonPrint map(Record r) {
		return new PersonPrint(
			r.get(PERSON_PRINT.UUID),
			r.get(PERSON_PRINT.PERSON_UUID),
			r.get(PERSON_PRINT.ATTACHMENT_UUID),
			r.get(PERSON_PRINT.TYPE),
			r.get(PERSON_PRINT.MODEL),
			r.get(PERSON_PRINT.DIMENSIONS),
			r.get(PERSON_PRINT.VECTOR),
			r.get(PERSON_PRINT.ERROR),
			toInstant(r.get(PERSON_PRINT.CREATED)),
			toInstant(r.get(PERSON_PRINT.EDITED)));
	}

	private static java.time.Instant toInstant(LocalDateTime ldt) {
		return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
	}
}
