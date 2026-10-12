package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.vectorconfig.VectorConfig;
import io.metaloom.loom.db.model.vectorconfig.VectorConfigDao;
import io.vertx.core.json.JsonObject;

/**
 * Coverage for {@link VectorConfigDao}: the inherited CRUD contract, the {@code loadByName} lookup, the {@code weights} JSONB round trip and the
 * constraints V2.119 added to {@code vector_config}.
 */
public class VectorConfigDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<VectorConfigDao, VectorConfig> {

	@Override
	public VectorConfigDao getDao() {
		return vectorConfigDao();
	}

	/**
	 * Build - but do not store - a config. {@code name} is UNIQUE (V2.6), and the harness builds 1024 of them, so it varies with {@code i}.
	 */
	@Override
	public VectorConfig createElement(User user, int i) {
		VectorConfig config = vectorConfigDao().createVectorConfig(user.getUuid(), "vector_config_" + i);
		config.setWeights(new JsonObject().put("caption", 0.7).put("tags", 0.3));
		return config;
	}

	@Override
	public void assertCreate(VectorConfig createdElement) {
		assertEquals("vector_config_0", createdElement.getName());
		assertNotNull(createdElement.getWeights());
		assertEquals(0.7, createdElement.getWeights().getDouble("caption"));
		assertEquals(0.3, createdElement.getWeights().getDouble("tags"));
		assertNotNull(createdElement.getCreatorUuid());
		assertNotNull(createdElement.getEditorUuid());
	}

	@Override
	public void updateElement(VectorConfig element) {
		element.setName("vector_config_renamed");
		element.setWeights(new JsonObject().put("faces", 1.0));
	}

	@Override
	public void assertUpdate(VectorConfig updatedElement) {
		assertEquals("vector_config_renamed", updatedElement.getName());
		assertEquals(1.0, updatedElement.getWeights().getDouble("faces"));
		assertNull(updatedElement.getWeights().getValue("caption"), "The update replaces the weights, it does not merge them");
	}

	@Test
	public void testLoadByName() {
		VectorConfig config = createElement(dummyUser(), 7);
		vectorConfigDao().store(config);

		VectorConfig loaded = vectorConfigDao().loadByName("vector_config_7");
		assertNotNull(loaded);
		assertEquals(config.getUuid(), loaded.getUuid());
		assertEquals(0.7, loaded.getWeights().getDouble("caption"));
	}

	@Test
	public void testLoadByNameNotFound() {
		assertNull(vectorConfigDao().loadByName("no_such_vector_config"));
	}

	/**
	 * Weights are optional - a config can be named before it is defined.
	 */
	@Test
	public void testWeightsMayBeEmpty() {
		VectorConfig config = vectorConfigDao().createVectorConfig(dummyUser().getUuid(), "vector_config_unweighted");
		vectorConfigDao().store(config);
		assertNull(vectorConfigDao().load(config.getUuid()).getWeights());
	}

	@Test
	public void testNameIsUnique() {
		vectorConfigDao().store(vectorConfigDao().createVectorConfig(dummyUser().getUuid(), "vector_config_twice"));
		VectorConfig second = vectorConfigDao().createVectorConfig(dummyUser().getUuid(), "vector_config_twice");
		assertThrows(DataAccessException.class, () -> vectorConfigDao().store(second));
	}

	/**
	 * V2.119: the actors are real foreign keys now. Before it, the integrity report had to find these rows after the fact
	 * ({@code DANGLING_VECTOR_CONFIG_ACTOR}).
	 */
	@Test
	public void testActorsMustBeRealUsers() {
		VectorConfig config = vectorConfigDao().createVectorConfig(UUID.randomUUID(), "vector_config_ghost_author");
		assertThrows(DataAccessException.class, () -> vectorConfigDao().store(config));
		assertNull(vectorConfigDao().loadByName("vector_config_ghost_author"));
	}

	/**
	 * Deleting one config leaves an identical sibling untouched.
	 */
	@Test
	public void testDeleteRemovesOnlyTheTarget() {
		VectorConfig doomed = createElement(dummyUser(), 11);
		vectorConfigDao().store(doomed);
		VectorConfig survivor = createElement(dummyUser(), 12);
		vectorConfigDao().store(survivor);

		vectorConfigDao().delete(doomed.getUuid());

		assertNull(vectorConfigDao().load(doomed.getUuid()));
		assertNotNull(vectorConfigDao().load(survivor.getUuid()));
		assertEquals(0.7, vectorConfigDao().load(survivor.getUuid()).getWeights().getDouble("caption"));
	}
}
