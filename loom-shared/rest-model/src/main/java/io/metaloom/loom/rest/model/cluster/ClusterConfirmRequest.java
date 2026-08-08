package io.metaloom.loom.rest.model.cluster;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Confirm that a cluster is a particular person.
 *
 * <p>
 * Two shapes in one request, because the reviewer is answering one question ("who is this?") either way:
 * </p>
 *
 * <ul>
 * <li>{@code personUuid} set - link this cluster to somebody already known.</li>
 * <li>{@code personUuid} absent - this is somebody new; create them from the names given here.</li>
 * </ul>
 *
 * <p>
 * The second form additionally requires the {@code CREATE_PERSON} permission, so a reviewer can be allowed to attribute faces to existing people
 * without being able to populate the person directory.
 * </p>
 */
public class ClusterConfirmRequest implements RestRequestModel {

	private String personUuid;

	private String alias;

	private String firstname;

	private String lastname;

	private String name;

	/** The person to link, or null to create one from the names below. */
	public String getPersonUuid() {
		return personUuid;
	}

	public ClusterConfirmRequest setPersonUuid(String personUuid) {
		this.personUuid = personUuid;
		return this;
	}

	/** Display name for a person being created. Ignored when {@link #getPersonUuid()} is set. */
	public String getAlias() {
		return alias;
	}

	public ClusterConfirmRequest setAlias(String alias) {
		this.alias = alias;
		return this;
	}

	public String getFirstname() {
		return firstname;
	}

	public ClusterConfirmRequest setFirstname(String firstname) {
		this.firstname = firstname;
		return this;
	}

	public String getLastname() {
		return lastname;
	}

	public ClusterConfirmRequest setLastname(String lastname) {
		this.lastname = lastname;
		return this;
	}

	/**
	 * Optional label for the cluster itself, distinct from the person's name.
	 *
	 * <p>
	 * A machine proposal has no name; a reviewer may want to give this particular grouping one ("Anna, back row") without renaming the person.
	 * </p>
	 */
	public String getName() {
		return name;
	}

	public ClusterConfirmRequest setName(String name) {
		this.name = name;
		return this;
	}

}
