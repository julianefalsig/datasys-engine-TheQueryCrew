package dk.itu.datasys.sql;

import dk.itu.datasys.storage.StorageEngine;

//Checks a parsed statement against the catalog

public final class Binder {

    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        this.engine = engine;
    }

    // Validates s against the catalog; throws IllegalArgumentException on the first violation.
    public void bind(Statement statement) {
        switch (statement) {
            // The file is execution's concern: it can appear or vanish between binding and running.
            case CopyStatement copy -> engine.schema(copy.tableName());
            case CreateTableStatement create -> throw new UnsupportedOperationException("not bound yet");
            case SelectStatement select -> throw new UnsupportedOperationException("not bound yet");
        }
    }
}
