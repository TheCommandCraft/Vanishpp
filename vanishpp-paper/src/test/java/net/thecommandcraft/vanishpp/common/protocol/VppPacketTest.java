package net.thecommandcraft.vanishpp.common.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class VppPacketTest {

    @Test
    void decode_rejectsInvalidLengthsBeforeAllocation() {
        byte[] valid = VppPacket.encode(VppMessage.PONG, "{\"text\":\"漢\"}");
        assertEquals("{\"text\":\"漢\"}", VppPacket.decode(valid).jsonPayload());
        assertEquals("", VppPacket.decode(VppPacket.encode(VppMessage.PONG, "")).jsonPayload());
        assertAll(
                () -> assertEquals(IllegalArgumentException.class, rejectionType(Integer.MAX_VALUE),
                        "oversized length must be rejected before allocation"),
                () -> assertEquals(IllegalArgumentException.class, rejectionType(-1),
                        "negative length must be rejected before allocation"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> VppPacket.decode(Arrays.copyOf(valid, valid.length - 1))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> VppPacket.decode(new byte[4])));
    }

    private byte[] frame(int length) {
        return ByteBuffer.allocate(5).put((byte) VppMessage.PONG.ordinal()).putInt(length).array();
    }

    private Class<?> rejectionType(int length) {
        try {
            VppPacket.decode(frame(length));
            return null;
        } catch (IllegalArgumentException | NegativeArraySizeException | OutOfMemoryError error) {
            // MAX_VALUE exceeds the VM's array limit without actually allocating a large heap array.
            // Keep the error out of JUnit's unrecoverable-error propagation so this fails at an assertion.
            return error.getClass();
        }
    }
}
