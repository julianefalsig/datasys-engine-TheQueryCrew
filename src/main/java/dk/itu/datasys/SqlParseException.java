package dk.itu.datasys;

public final class SqlParseException extends RuntimeException {

    private final int line;
    private final int column;

    public SqlParseException(String message, int line, int column) {
        super(message);
        this.line = line;
        this.column = column;
    }

    public SqlParseException(String message, int line, int column, Throwable cause) {
        super(message, cause);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
