package io.metaloom.loom.test.integration.node.docs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.metaloom.cortex.api.node.NodeResult;
import io.vertx.core.json.JsonObject;

/**
 * One node's contribution to the documentation: how to run it, and what it needs in order to run.
 *
 * <p>
 * Deliberately a description rather than a test. The generator has to be able to answer "can this
 * one run here?" <em>before</em> constructing the node, so that an unsatisfied requirement aborts
 * with the command that would satisfy it instead of failing somewhere inside a native library.
 * </p>
 */
public interface DocsFixtureRecipe {

	/** The node kind, which is also the fixture directory name. */
	String kind();

	/** What must be true for {@link #run} to work. */
	Requirement requirement();

	/** Run the node and return what it produced. */
	Outcome run(FixtureEnv env) throws Exception;

	/**
	 * Whether the node talked to the real thing.
	 *
	 * <p>
	 * {@code "stub"} is legal only for the two cloud sources, where the stub replaces Google or
	 * Microsoft and everything below it is genuinely exercised. Everywhere else a stubbed backend
	 * means the picture shows a decision nothing made — several node tests inject canned clients,
	 * and one of them literally paints a gradient — so the capture refuses those.
	 * </p>
	 */
	default String backend() {
		return "real";
	}

	/**
	 * The node(s) whose output feeds this one, for the graph the screenshot is taken of.
	 *
	 * <p>
	 * A node photographed on its own is a card with nothing arriving at it. Naming the upstream here
	 * rather than in the capture script keeps the picture consistent with the run: whichever branch
	 * of an either/or input the fixture actually used is the one drawn.
	 * </p>
	 */
	default List<Upstream> upstream() {
		return List.of(new Upstream("filesystem-source", "media", "media"));
	}

	/** What a run produced, plus the file it ran over and the options it used. */
	record Outcome(NodeResult result, String mediaPath, JsonObject nodeData) {
		public Outcome(NodeResult result, String mediaPath) {
			this(result, mediaPath, new JsonObject());
		}
	}

	/** An edge into this node, in the graph the screenshot shows. */
	record Upstream(String kind, String sourcePort, String targetPort) {
	}

	/**
	 * A precondition, and the command that satisfies it.
	 *
	 * <p>
	 * The message matters as much as the check. "depthmap skipped" tells a maintainer nothing; "the
	 * depth sidecar is not answering on 9120 — run sidecars/depth/run.sh" tells them exactly what to
	 * do, which is the difference between a gap that gets closed and one that becomes permanent.
	 * </p>
	 */
	interface Requirement {

		boolean satisfied();

		String describe();

		/** What to run to make it satisfied. */
		String hint();

		/** Nothing beyond a JVM and the test corpus. */
		static Requirement offline() {
			return of(true, "offline", "nothing — this node needs no service, model or native runtime");
		}

		/** A native runtime that is either installed or not. */
		static Requirement nativeLib(String name, boolean present, String hint) {
			return of(present, "native:" + name, hint);
		}

		/** A file that must exist, such as a model pack or a set of weights. */
		static Requirement file(Path path, String hint) {
			return of(Files.isReadable(path), "file:" + path, hint);
		}

		/** An HTTP service that must answer. */
		static Requirement service(String name, String healthUrl, String hint) {
			return of(Probe.answering(healthUrl), "service:" + name + " (" + healthUrl + ")", hint);
		}

		/**
		 * A requirement whose check the caller performs itself.
		 *
		 * <p>
		 * For preconditions that are not "is this port open" or "does this file exist" — the vision
		 * nodes, for instance, need the server on the port to be a <em>multimodal</em> one, and a
		 * text model there has to read as unavailable rather than as a backend.
		 * </p>
		 */
		static Requirement of(boolean ok, String describe, String hint) {
			return new Requirement() {
				@Override
				public boolean satisfied() {
					return ok;
				}

				@Override
				public String describe() {
					return describe;
				}

				@Override
				public String hint() {
					return hint;
				}
			};
		}
	}
}
