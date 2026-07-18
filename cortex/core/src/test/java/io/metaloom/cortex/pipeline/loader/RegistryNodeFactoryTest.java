package io.metaloom.cortex.pipeline.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.vertx.core.json.JsonObject;

class RegistryNodeFactoryTest {

	private RegistryNodeFactory factory;

	@BeforeEach
	void setUp() {
		factory = new RegistryNodeFactory();
	}

	private static PipelineNode node(String id) {
		return new AbstractPipelineNode(id, id, NodeMode.PARALLEL, true, 1) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				return NodeResult.success(id(), 0);
			}
		};
	}

	private static JsonObject def(String type) {
		return new JsonObject().put("id", "n1").put("type", type);
	}

	@Test
	void testRegisteredTypeResolvesToItsProducer() {
		factory.register("sha512", def -> node("sha512"));

		PipelineNode resolved = factory.createNode(def("sha512"));

		assertThat(resolved).isNotNull();
		assertThat(resolved.id()).isEqualTo("sha512");
	}

	@Test
	void testTypeLookupIsCaseInsensitive() {
		factory.register("SHA512", def -> node("sha512"));

		assertThat(factory.createNode(def("sha512"))).isNotNull();
		assertThat(factory.createNode(def("Sha512"))).isNotNull();
		assertThat(factory.createNode(def("SHA512"))).isNotNull();
	}

	@Test
	void testProducerReceivesTheFullNodeDefinition() {
		AtomicReference<JsonObject> seen = new AtomicReference<>();
		factory.register("thumbnail", def -> {
			seen.set(def);
			return node("thumbnail");
		});

		JsonObject definition = def("thumbnail").put("options", new JsonObject().put("width", 320));
		factory.createNode(definition);

		assertThat(seen.get()).isSameAs(definition);
		assertThat(seen.get().getJsonObject("options").getInteger("width")).isEqualTo(320);
	}

	@Test
	void testRegisteringTheSameTypeTwiceOverwrites() {
		factory.register("md5", def -> node("first"));
		factory.register("md5", def -> node("second"));

		assertThat(factory.createNode(def("md5")).id()).isEqualTo("second");
		assertThat(factory.registeredTypes()).containsExactly("md5");
	}

	@Test
	void testRegisterRejectsBlankTypeAndNullProducer() {
		assertThatThrownBy(() -> factory.register(null, def -> node("x")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("type must not be null or blank");
		assertThatThrownBy(() -> factory.register("  ", def -> node("x")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> factory.register("md5", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("producer must not be null");
	}

	@Test
	void testRegisteredTypesAreReportedInRegistrationOrderAndAreUnmodifiable() {
		factory.register("sha512", def -> node("a"));
		factory.register("md5", def -> node("b"));
		factory.register("thumbnail", def -> node("c"));

		assertThat(factory.registeredTypes()).containsExactly("sha512", "md5", "thumbnail");
		assertThatThrownBy(() -> factory.registeredTypes().add("extra"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	/**
	 * An unregistered type resolves to {@code null}, which
	 * the factory currently turns into a stub node that logs and
	 * returns success — so a pipeline built entirely from unimplemented node
	 * types runs green. Making that a hard failure is Task 3; this pins the
	 * factory's half of the contract, which is unchanged by it.
	 */
	@Test
	void testUnregisteredTypeResolvesToNull() {
		factory.register("sha512", def -> node("sha512"));

		assertThat(factory.createNode(def("whisper"))).isNull();
	}

	@Test
	void testMissingOrBlankTypeResolvesToNull() {
		assertThat(factory.createNode(new JsonObject().put("id", "n1"))).isNull();
		assertThat(factory.createNode(new JsonObject().put("id", "n1").put("type", ""))).isNull();
	}

	@Test
	void testNullDefinitionResolvesToNull() {
		assertThat(factory.createNode(null)).isNull();
	}

	/**
	 * A producer that blows up must not take the whole pipeline load down with
	 * it — the node degrades to the stub path instead.
	 */
	@Test
	void testThrowingProducerResolvesToNull() {
		factory.register("facedetect", def -> {
			throw new IllegalStateException("native library not available");
		});

		assertThat(factory.createNode(def("facedetect"))).isNull();
	}

	@Test
	void testProducerReturningNullResolvesToNull() {
		factory.register("ocr", def -> null);

		assertThat(factory.createNode(def("ocr"))).isNull();
	}
}
