package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.db.model.user.User;

public class PersonDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PersonDao, Person> {

	@Override
	public Person createElement(User user, int i) {
		return personDao().createPerson(user, "person_" + i);
	}

	@Override
	public void assertCreate(Person createdElement) {
		assertEquals("person_0", createdElement.getAlias());
	}

	@Override
	public PersonDao getDao() {
		return personDao();
	}

	@Override
	public void updateElement(Person element) {
		element.setAlias("UpdatedAlias");
		element.setFirstname("John");
		element.setLastname("Doe");
	}

	@Override
	public void assertUpdate(Person updatedPerson) {
		assertEquals("UpdatedAlias", updatedPerson.getAlias());
		assertEquals("John", updatedPerson.getFirstname());
		assertEquals("Doe", updatedPerson.getLastname());
	}

}
