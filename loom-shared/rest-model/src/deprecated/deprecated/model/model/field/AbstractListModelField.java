package deprecated.model.model.field;

public abstract class AbstractListModelField extends AbstractModelField implements ListModelField {

	@Override
	public FieldType getType() {
		return FieldType.LIST;
	}
}
