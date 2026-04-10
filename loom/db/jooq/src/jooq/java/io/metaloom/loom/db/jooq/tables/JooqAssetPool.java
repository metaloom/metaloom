package io.metaloom.loom.db.jooq.tables;

import io.metaloom.loom.db.jooq.JooqPublic;
import io.metaloom.loom.db.jooq.converter.JsonObjectConverter;
import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;
import org.jooq.impl.TableRecordImpl;

/**
 * Storage pools for asset binaries.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class JooqAssetPool extends TableImpl<JooqAssetPool.JooqAssetPoolRecord> {

	private static final long serialVersionUID = 1L;

	public static final JooqAssetPool ASSET_POOL = new JooqAssetPool();

	public static class JooqAssetPoolRecord extends TableRecordImpl<JooqAssetPoolRecord> {
		public JooqAssetPoolRecord() {
			super(ASSET_POOL);
		}
	}

	@Override
	public Class<JooqAssetPoolRecord> getRecordType() {
		return JooqAssetPoolRecord.class;
	}

	public final TableField<JooqAssetPoolRecord, java.util.UUID> UUID = createField(DSL.name("uuid"),
		SQLDataType.UUID.nullable(false).defaultValue(DSL.field("uuid_generate_v4()", SQLDataType.UUID)), this, "");

	public final TableField<JooqAssetPoolRecord, String> NAME = createField(DSL.name("name"),
		SQLDataType.VARCHAR.nullable(false), this, "");

	public final TableField<JooqAssetPoolRecord, JsonObject> META = createField(DSL.name("meta"),
		SQLDataType.JSONB, this, "", new JsonObjectConverter());

	public final TableField<JooqAssetPoolRecord, String> FS_PATH = createField(DSL.name("fs_path"),
		SQLDataType.VARCHAR, this, "");

	public final TableField<JooqAssetPoolRecord, String> S3_BUCKET = createField(DSL.name("s3_bucket"),
		SQLDataType.VARCHAR, this, "");

	public final TableField<JooqAssetPoolRecord, String> S3_REGION = createField(DSL.name("s3_region"),
		SQLDataType.VARCHAR, this, "");

	public final TableField<JooqAssetPoolRecord, String> S3_ENDPOINT = createField(DSL.name("s3_endpoint"),
		SQLDataType.VARCHAR, this, "");

	public final TableField<JooqAssetPoolRecord, LocalDateTime> CREATED = createField(DSL.name("created"),
		SQLDataType.LOCALDATETIME(6).nullable(false).defaultValue(DSL.field("now()", SQLDataType.LOCALDATETIME)), this, "");

	public final TableField<JooqAssetPoolRecord, java.util.UUID> CREATOR_UUID = createField(DSL.name("creator_uuid"),
		SQLDataType.UUID.nullable(false), this, "");

	public final TableField<JooqAssetPoolRecord, LocalDateTime> EDITED = createField(DSL.name("edited"),
		SQLDataType.LOCALDATETIME(6).nullable(false).defaultValue(DSL.field("now()", SQLDataType.LOCALDATETIME)), this, "");

	public final TableField<JooqAssetPoolRecord, java.util.UUID> EDITOR_UUID = createField(DSL.name("editor_uuid"),
		SQLDataType.UUID.nullable(false), this, "");

	private JooqAssetPool(Name alias, Table<JooqAssetPoolRecord> aliased, Field<?>[] parameters) {
		super(alias, null, aliased, parameters, DSL.comment("Storage pools for asset binaries"), TableOptions.table());
	}

	private JooqAssetPool(Name alias, Table<JooqAssetPoolRecord> aliased) {
		this(alias, aliased, null);
	}

	public JooqAssetPool() {
		this(DSL.name("asset_pool"), null);
	}

	public JooqAssetPool(String alias) {
		this(DSL.name(alias), ASSET_POOL);
	}

	public JooqAssetPool(Name alias) {
		this(alias, ASSET_POOL);
	}

	public <O extends Record> JooqAssetPool(Table<O> child, ForeignKey<O, JooqAssetPoolRecord> key) {
		super(child, key, ASSET_POOL);
	}

	@Override
	public Schema getSchema() {
		return aliased() ? null : JooqPublic.PUBLIC;
	}

	@Override
	public JooqAssetPool as(String alias) {
		return new JooqAssetPool(DSL.name(alias), this);
	}

	@Override
	public JooqAssetPool as(Name alias) {
		return new JooqAssetPool(alias, this);
	}

	@Override
	public JooqAssetPool as(Table<?> alias) {
		return new JooqAssetPool(alias.getQualifiedName(), this);
	}

	@Override
	public JooqAssetPool rename(String name) {
		return new JooqAssetPool(DSL.name(name), null);
	}

	@Override
	public JooqAssetPool rename(Name name) {
		return new JooqAssetPool(name, null);
	}
}
