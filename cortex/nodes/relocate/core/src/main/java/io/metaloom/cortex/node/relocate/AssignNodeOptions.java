package io.metaloom.cortex.node.relocate;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Per-instance configuration for the {@code assign} node.
 */
public class AssignNodeOptions extends AbstractNodeOptions<AssignNodeOptions> {

	public static final String KEY = "assign";

	@ParamDoc(label = "Target", description = "What to add the asset to: a collection or a library", order = 10)
	private AssignTarget target = AssignTarget.COLLECTION;

	@ParamDoc(label = "Collection Uuid", description = "The collection to add to. Use this or collectionName, not both", order = 20)
	private String collectionUuid;

	@ParamDoc(label = "Collection Name", description = "The collection to add to, resolved by name. Use this or collectionUuid, not both", order = 30)
	private String collectionName;

	@ParamDoc(label = "Library Uuid", description = "The library to add to, for the LIBRARY target", order = 40)
	private String libraryUuid;

	@ParamDoc(label = "On Missing", description = "What to do when the target does not exist: fail, create it, or skip the item", order = 50)
	private String onMissing = "FAIL";

	@ParamDoc(label = "Dry Run", description = "Report the membership that would be written and write nothing", order = 60)
	private boolean dryRun = false;

	public AssignTarget getTarget() {
		return target;
	}

	public AssignNodeOptions setTarget(AssignTarget target) {
		this.target = target;
		return this;
	}

	public String getCollectionUuid() {
		return collectionUuid;
	}

	public AssignNodeOptions setCollectionUuid(String collectionUuid) {
		this.collectionUuid = collectionUuid;
		return this;
	}

	public String getCollectionName() {
		return collectionName;
	}

	public AssignNodeOptions setCollectionName(String collectionName) {
		this.collectionName = collectionName;
		return this;
	}

	public String getLibraryUuid() {
		return libraryUuid;
	}

	public AssignNodeOptions setLibraryUuid(String libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}

	public String getOnMissing() {
		return onMissing;
	}

	public AssignNodeOptions setOnMissing(String onMissing) {
		this.onMissing = onMissing;
		return this;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public AssignNodeOptions setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());

		if (target == null) {
			errors.add("target must be set");
		}
		try {
			OnMissing.parse(onMissing);
		} catch (IllegalArgumentException e) {
			errors.add("onMissing: " + e.getMessage());
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}

	@Override
	protected AssignNodeOptions self() {
		return this;
	}
}
