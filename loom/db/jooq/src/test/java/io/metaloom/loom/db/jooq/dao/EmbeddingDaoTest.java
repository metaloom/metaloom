package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.db.model.user.User;

public class EmbeddingDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<EmbeddingDao, Embedding> {

	@Override
	public EmbeddingDao getDao() {
		return embeddingDao();
	}

	@Override
	public Embedding createElement(User user, int i) {
		Embedding embedding = getDao().createEmbedding(user, asset(), VECTOR_DATA, EmbeddingType.DLIB_FACE_RESNET_v1.name());
		embedding.setNodeKind("facedetect");
		embedding.setProducerVersion("dlib-v1");
		// The identity is (asset, node kind, type, frame, subject) - vary the subject so a test
		// that needs several embeddings of one asset does not collide on it.
		embedding.setSubjectIndex(i);
		return embedding;
	}

	@Override
	public void assertCreate(Embedding createdElement) {
		assertEquals(asset().getUuid(), createdElement.getAssetUuid());
		assertEquals(EmbeddingType.DLIB_FACE_RESNET_v1.name(), createdElement.getType());
		assertEquals("facedetect", createdElement.getNodeKind());
		assertEquals(VECTOR_DATA.length, createdElement.getDimensions().intValue());
	}

	@Override
	public void updateElement(Embedding element) {
		// Not "manual": the test fixture already owns (asset, manual, DLIB_FACE_RESNET_v1, 0, 0).
		element.setNodeKind("facedescription");
	}

	@Override
	public void assertUpdate(Embedding updatedElement) {
		assertEquals("facedescription", updatedElement.getNodeKind());
	}

	@Test
	public void shouldKeepTwoModelsSideBySide() {
		// The reason `model` joined the identity key in V2.75. Before it, re-running a node under a new
		// model upserted over the old row, so a model upgrade was destructive and irreversible: there
		// was no moment at which both sets existed and could be compared.
		Embedding old = getDao().createEmbedding(dummyUser(), asset(), VECTOR_DATA, "face");
		old.setNodeKind("facedetect").setModel("inspireface-r18").setSubjectIndex(0);
		getDao().upsertEmbedding(old);

		Embedding fresh = getDao().createEmbedding(dummyUser(), asset(), VECTOR_DATA, "face");
		fresh.setNodeKind("facedetect").setModel("some-newer-model").setSubjectIndex(0);
		getDao().upsertEmbedding(fresh);

		assertNotEquals(old.getUuid(), fresh.getUuid(), "The two models must not collapse onto one row");
		assertNotNull(getDao().load(old.getUuid()), "The older model's embedding must survive the newer one");
		assertEquals("inspireface-r18", getDao().load(old.getUuid()).getModel());
	}

	@Test
	public void shouldUpsertRatherThanDuplicateForTheSameModel() {
		Embedding first = getDao().createEmbedding(dummyUser(), asset(), VECTOR_DATA, "face");
		first.setNodeKind("facedetect").setModel("inspireface-r18").setSubjectIndex(3);
		getDao().upsertEmbedding(first);

		Embedding rerun = getDao().createEmbedding(dummyUser(), asset(), VECTOR_DATA, "face");
		rerun.setNodeKind("facedetect").setModel("inspireface-r18").setSubjectIndex(3);
		getDao().upsertEmbedding(rerun);

		assertEquals(first.getUuid(), rerun.getUuid(), "A node re-run must rewrite its own row, not append a second");
	}

	@Test
	public void shouldStartDirtyAndClearOnSync() {
		Embedding embedding = getDao().createEmbedding(dummyUser(), asset(), VECTOR_DATA, "face");
		embedding.setNodeKind("facedetect").setModel("inspireface-r18").setSubjectIndex(7);
		getDao().upsertEmbedding(embedding);

		// New rows start dirty, which is what lets the index drain find work that the write hook missed.
		assertTrue(getDao().load(embedding.getUuid()).getDirty(), "A new embedding must start dirty");
		assertTrue(getDao().findDirty(100).stream().anyMatch(e -> embedding.getUuid().equals(e.getUuid())));

		getDao().markSynced(List.of(embedding.getUuid()));

		assertFalse(getDao().load(embedding.getUuid()).getDirty(), "markSynced must clear the flag");
		assertTrue(getDao().findDirty(100).stream().noneMatch(e -> embedding.getUuid().equals(e.getUuid())));
	}

	@Test
	public void shouldDeriveDimensionsFromTheVector() {
		// The V2.75 CHECK rejects a row whose dimensions and vector length disagree, so the DAO derives
		// it rather than trusting the caller - a whole class of constraint violations made unreachable.
		Embedding embedding = getDao().createEmbedding(dummyUser(), asset(), VECTOR_DATA, "face");
		embedding.setNodeKind("facedetect").setModel("inspireface-r18").setSubjectIndex(9);
		getDao().upsertEmbedding(embedding);

		assertEquals(VECTOR_DATA.length, getDao().load(embedding.getUuid()).getDimensions().intValue());
	}

}
