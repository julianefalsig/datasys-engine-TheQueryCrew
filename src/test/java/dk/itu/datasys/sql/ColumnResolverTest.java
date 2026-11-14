package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ColumnResolverTest {

    private static final List<ColumnSpec> ORDERS = List.of(
            new ColumnSpec("o_orderkey", ColumnType.LONG),
            new ColumnSpec("o_custname", ColumnType.STRING),
            new ColumnSpec("o_prodname", ColumnType.STRING),
            new ColumnSpec("o_quantity", ColumnType.LONG));

    private static final List<ColumnSpec> CUSTOMERS = List.of(
            new ColumnSpec("c_name", ColumnType.STRING),
            new ColumnSpec("c_city", ColumnType.STRING));

    private static final List<ColumnSpec> PRODUCTS = List.of(
            new ColumnSpec("p_name", ColumnType.STRING),
            new ColumnSpec("p_price", ColumnType.DOUBLE));

    private final ColumnResolver resolver = new ColumnResolver(
            List.of("orders", "customers", "products"),
            List.of(ORDERS, CUSTOMERS, PRODUCTS));

    // orders has four columns, so c_city is the sixth column of the concatenated row.
    @Test
    void aColumnMapsToItsTableAndItsIndexInTheJoinedRow() {
        ColumnResolver.ResolvedColumn city = resolver.resolve("c_city");

        assertEquals("customers", city.tableName());
        assertEquals(5, city.rowIndex());
        assertEquals(ColumnType.STRING, city.column().type());
    }

    @Test
    void theFirstColumnOfTheFirstTableIsIndexZero() {
        ColumnResolver.ResolvedColumn orderKey = resolver.resolve("o_orderkey");

        assertEquals("orders", orderKey.tableName());
        assertEquals(0, orderKey.rowIndex());
    }

    // Four order columns and two customer columns put p_name at 6 and p_price at 7.
    @Test
    void aLaterTableContinuesTheConcatenatedRow() {
        ColumnResolver.ResolvedColumn price = resolver.resolve("p_price");

        assertEquals("products", price.tableName());
        assertEquals(7, price.rowIndex());
        assertEquals(ColumnType.DOUBLE, price.column().type());
    }

    @Test
    void anUnknownColumnThrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("missing"));

        assertEquals("unknown column: missing in tables [orders, customers, products]", error.getMessage());
    }

    @Test
    void aNameSharedByTwoTablesThrows() {
        ColumnResolver shared = new ColumnResolver(
                List.of("orders", "customers"),
                List.of(List.of(new ColumnSpec("id", ColumnType.LONG), new ColumnSpec("o_custname", ColumnType.STRING)),
                        List.of(new ColumnSpec("id", ColumnType.LONG), new ColumnSpec("c_name", ColumnType.STRING))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> shared.resolve("id"));

        assertEquals("ambiguous column: id in tables [orders, customers]", error.getMessage());
        assertEquals(3, shared.resolve("c_name").rowIndex());
    }
}
