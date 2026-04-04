package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.READ_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PIPELINE;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class PipelineEndpointService extends AbstractCRUDEndpointService<PipelineDao, Pipeline> {

	@Inject
	public PipelineEndpointService(PipelineDao pipelineDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(pipelineDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_PIPELINE, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_PIPELINE, modelBuilder::toPipelineList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_PIPELINE, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_PIPELINE, () -> {
			PipelineCreateRequest request = lrc.requestBody(PipelineCreateRequest.class);
			validator.validate(request);

			String name = request.getName();
			UUID userUuid = lrc.userUuid();
			Pipeline pipeline = dao().createPipeline(userUuid, name);
			pipeline.setDescription(request.getDescription());
			pipeline.setDefinition(request.getDefinition());
			if (request.isEnabled() != null) {
				pipeline.setEnabled(request.isEnabled());
			}
			if (request.getPriority() != null) {
				pipeline.setPriority(request.getPriority());
			}
			if (request.isDryRun() != null) {
				pipeline.setDryRun(request.isDryRun());
			}
			update(request::getMeta, pipeline::setMeta);
			return pipeline;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_PIPELINE, () -> {
			PipelineUpdateRequest request = lrc.requestBody(PipelineUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Pipeline pipeline = dao().load(id);
			update(request::getName, pipeline::setName);
			update(request::getDescription, pipeline::setDescription);
			update(request::getDefinition, pipeline::setDefinition);
			if (request.isEnabled() != null) {
				pipeline.setEnabled(request.isEnabled());
			}
			if (request.getPriority() != null) {
				pipeline.setPriority(request.getPriority());
			}
			if (request.isDryRun() != null) {
				pipeline.setDryRun(request.isDryRun());
			}
			update(request::getMeta, pipeline::setMeta);
			setEditor(pipeline, userUuid);
			return pipeline;
		}, modelBuilder::toResponse);
	}

}
