package dk.itu.datasys.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvParserTest {

    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void wellFormedLineParsesTypedValuesByPosition() {
        Object[] values = CsvParser.parseLine("Copenhagen,12,23.5", COLUMNS, "trips.csv", 1);
        assertArrayEquals(new Object[] {"Copenhagen", 12L, 23.5}, values);
    }

    @Test
    void malformedValueNamesFileAndLine() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CsvParser.parseLine("Copenhagen,not-a-long,23.5", COLUMNS, "trips.csv", 4));
        assertEquals("malformed value in trips.csv at line 4, column distance: 'not-a-long'", error.getMessage());
    }

    @Test
    void wrongFieldCountNamesFileAndLine() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CsvParser.parseLine("Copenhagen,12", COLUMNS, "trips.csv", 2));
        assertEquals("wrong field count in trips.csv at line 2: expected 3, got 2", error.getMessage());
    }
}
