package io.metaloom.loom.pipeline;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_MD5;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_AUDIO;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_VIDEO;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.SCALAR_INTEGER;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.STRUCT_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.STRUCT_JSON;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_PLAIN;

import java.util.List;

import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.PortGroup;
import io.metaloom.loom.nodes.spec.PortSpec;

/**
 * A small vocabulary of node kinds, shaped for testing the port rules and the fan-out engine.
 *
 * <p>Deliberately synthetic. Real node kinds do not offer every combination the analyzer and the
 * engine have to decide about — nothing shipping today declares a {@code detection/* ONE} input,
 * so there is no real pair that produces a second-level per-element chain — and pinning these
 * tests to whichever real node happens to have the right shape would break them every time that
 * node is re-specified. The <em>shapes</em> are what is under test, not the catalogue.</p>
 */
public final class TestDescriptors {

	private TestDescriptors() {
	}

	public static NodeDescriptorRegistry registry() {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();

		registry.register(new NodeDescriptor().setKind("test-source").setName("Source")
			.setCategory(NodeCategory.SOURCE)
			.setInputPorts(List.of())
			.setOutputPorts(List.of(PortSpec.one("media", MEDIA_ANY))));

		// One media item in, N texts out: the canonical fan-out driver.
		registry.register(new NodeDescriptor().setKind("splitter").setName("Splitter")
			.setCategory(NodeCategory.TRANSFORM)
			.setInputPorts(List.of(PortSpec.one("media", MEDIA_ANY)))
			.setOutputPorts(List.of(PortSpec.many("texts", TEXT_PLAIN), PortSpec.one("count", SCALAR_INTEGER))));

		// Media in, one text out - a plain link in a chain.
		registry.register(new NodeDescriptor().setKind("describe").setName("Describe")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("media", MEDIA_ANY)))
			.setOutputPorts(List.of(PortSpec.one("text", TEXT_PLAIN))));

		// One text in, one text out. Downstream of a sequence this runs once per element.
		registry.register(new NodeDescriptor().setKind("worker").setName("Worker")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("text", TEXT_PLAIN)))
			.setOutputPorts(List.of(PortSpec.one("result", TEXT_PLAIN))));

		// One text in, one structured verdict out - the second branch of the §6.5 scenario.
		registry.register(new NodeDescriptor().setKind("scorer").setName("Scorer")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("text", TEXT_PLAIN)))
			.setOutputPorts(List.of(PortSpec.one("score", STRUCT_JSON))));

		// A sequence in: this is the gather, and it runs once however long the sequence is.
		registry.register(new NodeDescriptor().setKind("collector").setName("Collector")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.many("items", TEXT_ANY)))
			.setOutputPorts(List.of(PortSpec.one("report", STRUCT_JSON))));

		// Two sequences in, recombined per origin asset - the workunit of §6.5.
		registry.register(new NodeDescriptor().setKind("gatherer").setName("Gatherer")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(
				PortSpec.many("summaries", TEXT_ANY),
				PortSpec.many("sentiments", STRUCT_ANY)))
			.setOutputPorts(List.of(PortSpec.one("report", STRUCT_JSON))));

		// Two single-element inputs: legal only when both trace to the same fan-out.
		registry.register(new NodeDescriptor().setKind("zipper").setName("Zipper")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("left", TEXT_PLAIN), PortSpec.one("right", TEXT_PLAIN)))
			.setOutputPorts(List.of(PortSpec.one("result", TEXT_PLAIN))));

		// One element in, a sequence out: fine on its own, nested fan-out below a driver.
		registry.register(new NodeDescriptor().setKind("resplitter").setName("Resplitter")
			.setCategory(NodeCategory.TRANSFORM)
			.setInputPorts(List.of(PortSpec.one("text", TEXT_PLAIN)))
			.setOutputPorts(List.of(PortSpec.many("parts", TEXT_PLAIN))));

		registry.register(new NodeDescriptor().setKind("hasher").setName("Hasher")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("digest", HASH_MD5)))
			.setOutputPorts(List.of(PortSpec.one("text", TEXT_PLAIN))));

		registry.register(new NodeDescriptor().setKind("either").setName("Either")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(
				PortSpec.one("audio", MEDIA_AUDIO).setGroup("media_alt"),
				PortSpec.one("video", MEDIA_VIDEO).setGroup("media_alt")))
			.setInputGroups(List.of(PortGroup.xor("media_alt", "Media")))
			.setOutputPorts(List.of(PortSpec.one("text", TEXT_PLAIN))));

		registry.register(new NodeDescriptor().setKind("either-optional").setName("Either (optional)")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(
				PortSpec.one("audio", MEDIA_AUDIO).setGroup("media_alt"),
				PortSpec.one("video", MEDIA_VIDEO).setGroup("media_alt")))
			.setInputGroups(List.of(PortGroup.optionalXor("media_alt", "Media")))
			.setOutputPorts(List.of(PortSpec.one("text", TEXT_PLAIN))));

		return registry;
	}
}
