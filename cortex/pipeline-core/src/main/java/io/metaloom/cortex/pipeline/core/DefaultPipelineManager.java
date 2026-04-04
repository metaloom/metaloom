package io.metaloom.cortex.pipeline.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineManager;

/**
 * Default pipeline manager. Maintains a thread-safe registry of pipelines and
 * resolves the best match by priority.
 */
public class DefaultPipelineManager implements PipelineManager {

	private final ConcurrentHashMap<String, Pipeline> pipelines = new ConcurrentHashMap<>();

	@Override
	public void register(Pipeline pipeline) {
		pipelines.put(pipeline.name(), pipeline);
	}

	@Override
	public boolean unregister(String name) {
		return pipelines.remove(name) != null;
	}

	@Override
	public List<Pipeline> pipelines() {
		List<Pipeline> list = new ArrayList<>(pipelines.values());
		list.sort(Comparator.comparingInt(Pipeline::priority).reversed());
		return list;
	}

	@Override
	public Optional<Pipeline> pipeline(String name) {
		return Optional.ofNullable(pipelines.get(name));
	}

	@Override
	public Optional<Pipeline> resolve(LoomMedia media) {
		return pipelines()
				.stream()
				.filter(Pipeline::isEnabled)
				.filter(p -> p.matches(media))
				.findFirst();
	}
}
