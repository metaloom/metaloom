package io.metaloom.loom.db.jooq.dao.person;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.person.Person;

public class PersonImpl extends AbstractEditableElement<Person> implements Person {

	private String alias;
	private String firstname;
	private String lastname;
	private UUID primaryImageUuid;

	@Override
	public String getAlias() {
		return alias;
	}

	@Override
	public Person setAlias(String alias) {
		this.alias = alias;
		return this;
	}

	@Override
	public String getFirstname() {
		return firstname;
	}

	@Override
	public Person setFirstname(String firstname) {
		this.firstname = firstname;
		return this;
	}

	@Override
	public String getLastname() {
		return lastname;
	}

	@Override
	public Person setLastname(String lastname) {
		this.lastname = lastname;
		return this;
	}

	@Override
	public UUID getPrimaryImageUuid() {
		return primaryImageUuid;
	}

	@Override
	public Person setPrimaryImageUuid(UUID primaryImageUuid) {
		this.primaryImageUuid = primaryImageUuid;
		return this;
	}

}
