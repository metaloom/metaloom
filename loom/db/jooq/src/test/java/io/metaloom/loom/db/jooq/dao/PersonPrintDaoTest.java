package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonPrint;
import io.metaloom.loom.db.model.person.PersonPrintDao;
import io.metaloom.loom.db.model.person.PersonPrintDao.PendingSample;
import io.metaloom.loom.db.model.person.PersonReference;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;

/**
 * Reference prints computed from a person's own samples (V2.115).
 */
public class PersonPrintDaoTest extends AbstractJooqTest {

	private static final Float[] VECTOR = { 0.6f, 0.8f };

	private static final Float[] OTHER_VECTOR = { 1f, 0f };

	private PersonPrintDao dao() {
		return daos().personPrintDao();
	}

	private SHA512 uniqueSha(int i) {
		return SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%04x", 0x9000 + i));
	}

	private Person person(User user, String alias) {
		Person person = personDao().createPerson(user, alias);
		personDao().store(person);
		return person;
	}

	private Attachment sample(User user, Person person, int i, AttachmentType type) {
		Attachment sample = attachmentDao().createAttachment(user.getUuid(), uniqueSha(i), "sample_" + i, 1024L, "audio/wav", type);
		sample.setPersonUuid(person.getUuid());
		attachmentDao().store(sample);
		return sample;
	}

	/**
	 * Embedding a sample again in the same space replaces its print instead of adding a second answer for one sample.
	 */
	@Test
	public void testUpsertReplacesThePrintOfOneSpace() {
		User user = adminUser();
		Person person = person(user, "print_person");
		Attachment sample = sample(user, person, 1, AttachmentType.PERSON_VOICE);

		PersonPrint first = dao().upsert(person.getUuid(), sample.getUuid(), "speaker", "model-a", null, "no speech found");
		PersonPrint second = dao().upsert(person.getUuid(), sample.getUuid(), "speaker", "model-a", VECTOR, null);
		dao().upsert(person.getUuid(), sample.getUuid(), "speaker", "model-b", OTHER_VECTOR, null);

		assertEquals(first.uuid(), second.uuid(), "The same row is updated");
		List<PersonPrint> prints = dao().listBySample(sample.getUuid());
		assertEquals(2, prints.size(), "One print per model");
		PersonPrint modelA = prints.get(0);
		assertEquals("model-a", modelA.model());
		assertTrue(modelA.usable(), "The retried print replaced the error");
		assertNull(modelA.error());
		assertEquals(2, modelA.dimensions());
		assertEquals(0.8f, modelA.vector()[1], 0.0001f);
	}

	/** A print is a vector or the reason there is none; anything else is refused before it reaches the CHECK. */
	@Test
	public void testUpsertRefusesAnAmbiguousOutcome() {
		User user = adminUser();
		Person person = person(user, "ambiguous_person");
		Attachment sample = sample(user, person, 2, AttachmentType.PERSON_VOICE);
		assertThrows(IllegalArgumentException.class, () -> dao().upsert(person.getUuid(), sample.getUuid(), "speaker", "m", null, null));
		assertThrows(IllegalArgumentException.class, () -> dao().upsert(person.getUuid(), sample.getUuid(), "speaker", "m", VECTOR, "both"));
	}

	/**
	 * The work queue lists every sample of the type that has no outcome in the space, oldest first - and a sample that failed is not queued again.
	 */
	@Test
	public void testListUnenrolled() {
		User user = adminUser();
		Person person = person(user, "queue_person");
		Attachment enrolled = sample(user, person, 3, AttachmentType.PERSON_VOICE);
		Attachment failed = sample(user, person, 4, AttachmentType.PERSON_VOICE);
		Attachment waiting = sample(user, person, 5, AttachmentType.PERSON_VOICE);
		Attachment picture = sample(user, person, 6, AttachmentType.PERSON_IMAGE);
		dao().upsert(person.getUuid(), enrolled.getUuid(), "speaker", "model-a", VECTOR, null);
		dao().upsert(person.getUuid(), failed.getUuid(), "speaker", "model-a", null, "no speech found");

		List<PendingSample> pending = dao().listUnenrolled("speaker", "model-a", AttachmentType.PERSON_VOICE, 10);
		assertTrue(pending.stream().anyMatch(p -> p.attachmentUuid().equals(waiting.getUuid())), "The unembedded sample is queued");
		assertTrue(pending.stream().noneMatch(p -> p.attachmentUuid().equals(enrolled.getUuid())), "An enrolled sample is not");
		assertTrue(pending.stream().noneMatch(p -> p.attachmentUuid().equals(failed.getUuid())), "A failed sample is not retried");
		assertTrue(pending.stream().noneMatch(p -> p.attachmentUuid().equals(picture.getUuid())), "Another sample type is not");
		PendingSample entry = pending.stream().filter(p -> p.attachmentUuid().equals(waiting.getUuid())).findFirst().orElseThrow();
		assertEquals(person.getUuid(), entry.personUuid());
		assertEquals("audio/wav", entry.mimeType());

		List<PendingSample> otherModel = dao().listUnenrolled("speaker", "model-b", AttachmentType.PERSON_VOICE, 10);
		assertTrue(otherModel.stream().anyMatch(p -> p.attachmentUuid().equals(enrolled.getUuid())),
			"A sample enrolled in one model is still pending in another");
		assertEquals(1, dao().listUnenrolled("speaker", "model-b", AttachmentType.PERSON_VOICE, 1).size(), "The limit applies");
	}

	/** Only usable prints of the requested space become references. */
	@Test
	public void testListUsableReferences() {
		User user = adminUser();
		Person person = person(user, "reference_person");
		Attachment usable = sample(user, person, 7, AttachmentType.PERSON_VOICE);
		Attachment broken = sample(user, person, 8, AttachmentType.PERSON_VOICE);
		dao().upsert(person.getUuid(), usable.getUuid(), "speaker", "model-ref", VECTOR, null);
		dao().upsert(person.getUuid(), broken.getUuid(), "speaker", "model-ref", null, "too short");
		dao().upsert(person.getUuid(), usable.getUuid(), "speaker", "model-other", OTHER_VECTOR, null);

		List<PersonReference> references = dao().listUsableReferences("speaker", "model-ref");
		assertEquals(1, references.size());
		assertEquals(person.getUuid(), references.get(0).personUuid());
		assertEquals(usable.getUuid(), references.get(0).sourceUuid());
		assertEquals(PersonReference.SOURCE_SAMPLE, references.get(0).source());
	}

	/**
	 * Deleting a sample takes its prints; deleting a person takes their samples and so their prints. A second person with an identical row set must
	 * come through both untouched.
	 */
	@Test
	public void testDeleteCascades() {
		User user = adminUser();
		Person doomed = person(user, "doomed_print_person");
		Person survivor = person(user, "surviving_print_person");
		Attachment doomedA = sample(user, doomed, 9, AttachmentType.PERSON_VOICE);
		Attachment doomedB = sample(user, doomed, 10, AttachmentType.PERSON_VOICE);
		Attachment keptA = sample(user, survivor, 11, AttachmentType.PERSON_VOICE);
		Attachment keptB = sample(user, survivor, 12, AttachmentType.PERSON_VOICE);
		for (Attachment a : List.of(doomedA, doomedB, keptA, keptB)) {
			dao().upsert(a.getPersonUuid(), a.getUuid(), "speaker", "model-cascade", VECTOR, null);
		}

		attachmentDao().delete(doomedA.getUuid());
		assertTrue(dao().listBySample(doomedA.getUuid()).isEmpty(), "The deleted sample's print is gone");
		assertEquals(1, dao().listBySample(doomedB.getUuid()).size(), "The person's other print is untouched");

		personDao().delete(doomed.getUuid());
		assertTrue(dao().listByPerson(doomed.getUuid()).isEmpty(), "The deleted person's prints are gone");
		assertEquals(2, dao().listByPerson(survivor.getUuid()).size(), "Another person's prints survive both deletes");
		assertNotEquals(0, dao().count());
	}
}
