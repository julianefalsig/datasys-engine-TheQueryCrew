package dk.itu.datasys.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The one place that says which Java value belongs to which column type, used by both the engine
// and the binder. Keeping it covered here means neither of them has to test the rule itself.
class ColumnTypeTest {

    @Test
    void eachTypeAcceptsOnlyItsOwnJavaType() {
        assertTrue(ColumnType.STRING.accepts("Copenhagen"));
        assertTrue(ColumnType.LONG.accepts(100L));
        assertTrue(ColumnType.DOUBLE.accepts(23.5));

        assertFalse(ColumnType.LONG.accepts("100"));
        assertFalse(ColumnType.DOUBLE.accepts(100L));
        assertFalse(ColumnType.STRING.accepts(100L));
    }

    // Integer is not Long: a caller writing 100 instead of 100L must be rejected, not silently widened.
    @Test
    void anIntegerIsNotALong() {
        assertFalse(ColumnType.LONG.accepts(100));
    }
}
