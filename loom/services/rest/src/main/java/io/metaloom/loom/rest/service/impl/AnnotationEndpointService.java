package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ANNOTATION;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ANNOTATION;
import static io.metaloom.loom.db.model.perm.Permission.READ_ANNOTATION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ANNOTATION;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.annotation.AnnotationDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.annotation.AnnotationCreateRequest;
import io.metaloom.loom.rest.model.annotation.AnnotationModel;
import io.metaloom.loom.rest.model.annotation.AnnotationUpdateRequest;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class AnnotationEndpointService extends AbstractCRUDEndpointService<AnnotationDao, Annotation> {

	@Inject
	public AnnotationEndpointService(AnnotationDao annotationDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(annotationDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_ANNOTATION, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_ANNOTATION, modelBuilder::toAnnotationList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_ANNOTATION, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_ANNOTATION, () -> {
			AnnotationCreateRequest request = lrc.requestBody(AnnotationCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			UUID assetUuid = request.getAssetUuid();
			String title = request.getTitle();
			AnnotationType type = request.getType();
			Annotation annotation = dao().createAnnotation(userUuid, assetUuid, title, type);
			update(request, annotation);
			return annotation;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_ANNOTATION, () -> {
			AnnotationUpdateRequest request = lrc.requestBody(AnnotationUpdateRequest.class);
			validator.validate(request);
			UUID userUuid = lrc.userUuid();

			Annotation annotation = dao().load(id);
			update(request, annotation);
			setEditor(annotation, userUuid);
			return annotation;
		}, modelBuilder::toResponse);
	}

	private void update(AnnotationModel<?> model, Annotation annotation) {
		update(model::getMeta, annotation::setMeta);
		update(model::getDescription, annotation::setDescription);
		update(model::getTitle, annotation::setTitle);

		AreaInfo areaInfo = model.getArea();
		if (areaInfo != null) {
			update(areaInfo::getHeight, annotation::setAreaHeight);
			update(areaInfo::getWidth, annotation::setAreaWidth);
			update(areaInfo::getStartX, annotation::setAreaStartX);
			update(areaInfo::getStartY, annotation::setAreaStartY);
			update(areaInfo::getFrom, annotation::setTimeFrom);
			update(areaInfo::getTo, annotation::setTimeTo);
		}
	}
}
