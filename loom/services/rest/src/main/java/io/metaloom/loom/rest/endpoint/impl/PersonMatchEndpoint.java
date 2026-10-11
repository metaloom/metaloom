package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.PersonMatchEndpointService;

/**
 * Person recognition: which known persons a face or a voice resembles.
 *
 * <p>
 * An RPC rather than a sub-resource of {@code /persons}: it creates nothing and addresses no person, and {@code POST /persons/match} would sit on the
 * same path shape as {@code POST /persons/:uuid}, the update route.
 * </p>
 */
public class PersonMatchEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(PersonMatchEndpoint.class);

	private final PersonMatchEndpointService service;

	private final ModelExamples examples;

	@Inject
	public PersonMatchEndpoint(PersonMatchEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "person-match";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/person-match";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		addRoute(basePath(), POST,
			"Find the known persons a set of face or voice vectors resembles, from the clusters reviewers confirmed and the persons' own samples. "
				+ "Returns candidates to suggest - it attributes nothing - and lists the samples that still need a print in the requested model.",
			examples.personMatchRequestExample(),
			examples.personMatchResponseExample(),
			lrc -> {
				service.match(lrc);
			});
	}
}
