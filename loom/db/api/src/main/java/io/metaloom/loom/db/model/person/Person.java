package io.metaloom.loom.db.model.person;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;

public interface Person extends CUDElement<Person> {

	String getAlias();

	Person setAlias(String alias);

	String getFirstname();

	Person setFirstname(String firstname);

	String getLastname();

	Person setLastname(String lastname);

	UUID getPrimaryImageUuid();

	Person setPrimaryImageUuid(UUID primaryImageUuid);

}
