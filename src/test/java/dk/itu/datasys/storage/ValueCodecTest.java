package dk.itu.datasys.storage;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValueCodecTest {

    @Test
    void longRoundTrip() {
        assertEquals(42L, encodeThenDecode(42L, ColumnType.LONG));
    }

    @Test
    void doubleRoundTrip() {
        assertEquals(23.5, encodeThenDecode(23.5, ColumnType.DOUBLE));
    }

    @Test
    void stringRoundTrip() {
        assertEquals("Copenhagen", encodeThenDecode("Copenhagen", ColumnType.STRING));
    }

    private static Object encodeThenDecode(Object value, ColumnType type) {
        ByteBuffer buffer = ValueCodec.newBuffer(ValueCodec.encodedSize(value, type));
        ValueCodec.encode(buffer, value, type);
        buffer.flip(); // flip the buffer to read the data from the correct position
        return ValueCodec.decode(buffer, type);
    }
}
