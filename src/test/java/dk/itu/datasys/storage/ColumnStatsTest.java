package dk.itu.datasys.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnStatsTest {

    @Test
    void minMaxOfSeveralLongs() {
        ColumnStats stats = ColumnStats.of(List.of(12L, 187L, 95L, 31L), ColumnType.LONG);
        assertEquals(12L, stats.min);
        assertEquals(187L, stats.max);
    }

    @Test
    void minMaxOfSeveralDoubles() {
        ColumnStats stats = ColumnStats.of(List.of(23.5, 301.0, 120.75, 45.0), ColumnType.DOUBLE);
        assertEquals(23.5, stats.min);
        assertEquals(301.0, stats.max);
    }

    @Test
    void minMaxOfSeveralStrings() {
        ColumnStats stats = ColumnStats.of(List.of("Copenhagen", "Aarhus", "Odense", "Aalborg"), ColumnType.STRING);
        assertEquals("Aalborg", stats.min);
        assertEquals("Odense", stats.max);
    }

    @Test
    void singleLongIsBothMinAndMax() {
        ColumnStats stats = ColumnStats.of(List.of(42L), ColumnType.LONG);
        assertEquals(42L, stats.min);
        assertEquals(42L, stats.max);
    }

    @Test
    void singleDoubleIsBothMinAndMax() {
        ColumnStats stats = ColumnStats.of(List.of(23.5), ColumnType.DOUBLE);
        assertEquals(23.5, stats.min);
        assertEquals(23.5, stats.max);
    }

    @Test
    void singleStringIsBothMinAndMax() {
        ColumnStats stats = ColumnStats.of(List.of("Odense"), ColumnType.STRING);
        assertEquals("Odense", stats.min);
        assertEquals("Odense", stats.max);
    }

    @Test
    void negativeLongs() {
        ColumnStats stats = ColumnStats.of(List.of(-3L, 0L, -12L, 4L), ColumnType.LONG);
        assertEquals(-12L, stats.min);
        assertEquals(4L, stats.max);
    }

    @Test
    void negativeDoubles() {
        ColumnStats stats = ColumnStats.of(List.of(-3.5, 0.0, -12.0, 4.25), ColumnType.DOUBLE);
        assertEquals(-12.0, stats.min);
        assertEquals(4.25, stats.max);
    }
}
