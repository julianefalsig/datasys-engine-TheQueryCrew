package dk.itu.datasys.storage;

public enum ColumnType {
    STRING, LONG, DOUBLE;

    // Whether a Java value can stand in for a value of this column type.
    public boolean accepts(Object constant) {
        return switch (this) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
    }
}
