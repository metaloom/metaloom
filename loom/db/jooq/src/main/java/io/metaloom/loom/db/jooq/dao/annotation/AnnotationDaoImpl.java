package io.metaloom.loom.db.jooq.dao.annotation;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationAsset.ANNOTATION_ASSET;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqAnnotation;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.annotation.AnnotationDao;

@Singleton
public class AnnotationDaoImpl extends AbstractJooqDao<Annotation> implements AnnotationDao {

	@Inject
	public AnnotationDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Annotations";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqAnnotation.ANNOTATION;
	}

	@Override
	protected Class<? extends Annotation> getPojoClass() {
		return AnnotationImpl.class;
	}

	@Override
	public Annotation createAnnotation(UUID userUuid, UUID assetUuid, String title, AnnotationType type) {
		Annotation annotation = new AnnotationImpl();
		annotation.setTitle(title);
		annotation.setAssetUuid(assetUuid);
		annotation.setType(type);
		setCreatorEditor(annotation, userUuid);
		return annotation;
	}

	@Override
	public List<Annotation> loadForAsset(UUID assetUuid) {
		return ctx().select(getTable())
			.from(getTable())
			.join(ANNOTATION_ASSET)
			.on(ANNOTATION_ASSET.ANNOTATION_UUID.eq(JooqAnnotation.ANNOTATION.UUID))
			.where(ANNOTATION_ASSET.ASSET_UUID.eq(assetUuid))
			.fetchInto(getPojoClass());
	}

}
