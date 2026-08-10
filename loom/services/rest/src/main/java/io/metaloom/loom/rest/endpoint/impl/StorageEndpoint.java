package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.StorageEndpointService;

/**
 * The storage report.
 *
 * <p>
 * Answers "what is filling my disk, and how close am I to full" - how many elements of each kind exist and what they occupy, how much of that is
 * duplicate content the store folded into one object, how many stored objects nothing references any more, and the free space and watermark of every
 * backend this deployment writes to.
 * </p>
 *
 * <p>
 * Singular path, deliberately, and for the same reason {@code /db-integrity} is: {@code CODING.md} reserves the singular for singleton resources, and
 * this is one report about one deployment's storage rather than a collection of storage objects. {@code /backends} underneath it <em>is</em> a
 * collection and is plural.
 * </p>
 *
 * <p>
 * Top-level rather than under an {@code /admin} namespace, because there is no such namespace - {@code /db-integrity}, {@code /metrics} and
 * {@code /search-indices} are all operator surfaces sitting at the root, and this would be the first and only exception.
 * </p>
 *
 * <p>
 * No POST. The report reads and computes; it writes nothing and reclaims nothing.
 * </p>
 */
@Singleton
public class StorageEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(StorageEndpoint.class);

	private final StorageEndpointService service;

	private final ModelExamples examples;

	@Inject
	public StorageEndpoint(StorageEndpointService service, ModelExamples examples, EndpointDependencies deps) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "storage";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/storage";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// Two routes under one base, so secure the subtree rather than the exact path.
		secure(basePath() + "*");

		// The literal segment first, matching the house rule that keeps /assets/upload from being
		// swallowed by /assets/:uuid. Both paths here are literal, so this is convention rather than
		// necessity - but the next route added under this base may not be.
		addRoute(basePath() + "/backends", GET,
			"List the storage backends and how full each one is, without the catalogue aggregates. Cheap enough to poll.",
			null,
			examples.storageBackendListExample(),
			lrc -> service.loadBackends(lrc));

		addRoute(basePath(), GET,
			"Report what is stored, per kind of content, and how much room is left on every storage backend.",
			null,
			examples.storageReportExample(),
			lrc -> service.loadReport(lrc));
	}
}
