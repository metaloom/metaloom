package io.metaloom.loom.db.jooq.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.loom.api.search.TextEmbedder;
import io.metaloom.loom.api.search.VectorSpace;

/**
 * A deterministic {@link TextEmbedder} for tests: no inference host, no model download, no network.
 *
 * <p>
 * Texts are mapped to vectors by a registered <em>topic</em>. Each topic owns one axis of a small space, so two texts on the same topic are identical
 * vectors and two texts on different topics are orthogonal. That is enough to test everything the provider actually has to get right - which documents
 * a query retrieves, in what order, and how the two rankers fuse - without pretending to test embedding quality, which belongs to the model and not to
 * this code.
 * </p>
 *
 * <p>
 * A topic can carry <b>aliases</b>, and they are what make these tests worth writing: a document reachable through a word it does not contain is the
 * whole difference between semantic and lexical retrieval. Without them every semantic hit would also be a lexical hit and the two rankers could not be
 * told apart.
 * </p>
 *
 * <p>
 * Unregistered text embeds to a zero vector, so "the query is about nothing in the corpus" is expressible and returns no neighbours.
 * </p>
 */
public class FakeTextEmbedder implements TextEmbedder {

	public static final int DIMENSIONS = 8;

	private final Map<String, Integer> axes = new LinkedHashMap<>();

	private int nextAxis = 0;

	private final VectorSpace space;

	private boolean available = true;

	private RuntimeException failure;

	public FakeTextEmbedder() {
		this("fake-embed-v1");
	}

	public FakeTextEmbedder(String model) {
		this.space = new VectorSpace("text", model, DIMENSIONS);
	}

	/**
	 * Register a topic, giving it its own axis. Every text containing the topic word - or any of its aliases - embeds onto that axis.
	 */
	public FakeTextEmbedder withTopic(String topic, String... aliases) {
		// An explicit counter, not axes.size(): aliases share their topic's axis while still occupying a map
		// entry, so sizing off the map would skip axes and eventually run past DIMENSIONS.
		int axis = axes.computeIfAbsent(topic.toLowerCase(), key -> nextAxis++);
		for (String alias : aliases) {
			axes.put(alias.toLowerCase(), axis);
		}
		return this;
	}

	public FakeTextEmbedder unavailable() {
		this.available = false;
		return this;
	}

	/** Make every embed call throw, standing in for an inference host that is up but broken. */
	public FakeTextEmbedder failing(RuntimeException failure) {
		this.failure = failure;
		return this;
	}

	@Override
	public String name() {
		return "fake";
	}

	@Override
	public boolean isAvailable() {
		return available;
	}

	@Override
	public VectorSpace space() {
		return space;
	}

	@Override
	public float[] embed(String text) {
		if (failure != null) {
			throw failure;
		}
		float[] vector = new float[DIMENSIONS];
		String haystack = text == null ? "" : text.toLowerCase();
		for (Map.Entry<String, Integer> entry : axes.entrySet()) {
			if (haystack.contains(entry.getKey())) {
				vector[entry.getValue()] = 1f;
			}
		}
		return normalize(vector);
	}

	@Override
	public List<float[]> embedAll(List<String> texts) {
		List<float[]> out = new ArrayList<>(texts.size());
		for (String text : texts) {
			out.add(embed(text));
		}
		return out;
	}

	private static float[] normalize(float[] vector) {
		double sum = 0;
		for (float component : vector) {
			sum += (double) component * component;
		}
		double length = Math.sqrt(sum);
		if (length == 0) {
			return vector;
		}
		for (int i = 0; i < vector.length; i++) {
			vector[i] = (float) (vector[i] / length);
		}
		return vector;
	}
}
