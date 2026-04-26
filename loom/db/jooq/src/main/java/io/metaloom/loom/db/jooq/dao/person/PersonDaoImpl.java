package io.metaloom.loom.db.jooq.dao.person;

import static io.metaloom.loom.db.jooq.tables.JooqPerson.PERSON;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqPerson;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonDao;

@Singleton
public class PersonDaoImpl extends AbstractJooqDao<Person> implements PersonDao {

	@Inject
	public PersonDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Persons";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqPerson.PERSON;
	}

	@Override
	protected Class<? extends Person> getPojoClass() {
		return PersonImpl.class;
	}

	@Override
	public Person createPerson(UUID userUuid, String alias) {
		Person person = new PersonImpl();
		person.setAlias(alias);
		setCreatorEditor(person, userUuid);
		return person;
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.NAME) {
			return query.and(PERSON.ALIAS.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}

}
